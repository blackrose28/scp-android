package run.scp.mobile.app.servers

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import run.scp.mobile.app.backup.BackupDecryptor
import run.scp.mobile.app.backup.BoundarySettings
import run.scp.mobile.app.backup.ImportedSecrets
import run.scp.mobile.app.backup.ServerEntry
import run.scp.mobile.app.secure.SecretStore

private class MemRepo(var stored: StoredServers = StoredServers.EMPTY) : ServerRepository {
    var failReplace: Throwable? = null
    var failLoad: Throwable? = null
    val replaced = AtomicInteger()
    override fun load(): StoredServers { failLoad?.let { throw it }; return stored }
    override fun replace(servers: StoredServers) { failReplace?.let { throw it }; replaced.incrementAndGet(); stored = servers }
}

private class MemSecrets(var secrets: ImportedSecrets = ImportedSecrets(null, null)) : SecretStore {
    var failSave: Throwable? = null
    override fun load() = secrets
    override fun save(secrets: ImportedSecrets) { failSave?.let { throw it }; this.secrets = secrets }
}

private val PLAIN = """{"schema":1,"files":{
    "config.json":{"boundary":{"addr":"https://b.example","authMethodId":"amoidc_1"}},
    "servers.json":{"servers":[
      {"id":"a","name":"A","host":"h1","connectionMethod":"ssh","sshPort":22,"sshUser":"u"},
      {"id":"b","name":"B","host":"h2","connectionMethod":"boundary","sshPort":22,"sshUser":"u","boundaryTargetId":"ttcp_1"}]}},
    "credentials":{"boundary_oidc_password":"pw","boundary_oidc_otp_secret":"otp"}}""".toByteArray()

private class FakeDecryptor(var result: () -> ByteArray = { PLAIN.copyOf() }) : BackupDecryptor {
    val calls = AtomicInteger()
    @Volatile var lastPassphrase: String? = null
    override fun decrypt(data: ByteArray, passphrase: String): ByteArray {
        calls.incrementAndGet(); lastPassphrase = passphrase
        return result()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val repo = MemRepo()
    private val secrets = MemSecrets()
    private val dec = FakeDecryptor()
    private val file = byteArrayOf(1, 2, 3)

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = ServersViewModel(dec, repo, secrets, ioDispatcher = Dispatchers.IO)

    private suspend fun <T> Flow<T>.await(p: (T) -> Boolean): T = withTimeout(5_000) { first(p) }

    private suspend fun ServersViewModel.toPreview(): ImportState.Preview {
        chooseFile("b.scpbak", file)
        decrypt("pw")
        return state.await { it is ImportState.Preview } as ImportState.Preview
    }

    @Test fun `stored servers are loaded at start`() = runBlocking<Unit> {
        repo.stored = StoredServers(listOf(ServerEntry("x", "X", "h", 22, "u", null)), null)
        val v = vm()
        assertEquals(listOf("x"), v.servers.await { it.servers.isNotEmpty() }.servers.map { it.id })
        assertNull(v.loadError.value)
    }

    @Test fun `an unreadable server file surfaces an error and an empty list`() = runBlocking<Unit> {
        repo.failLoad = IOException("corrupt")
        val v = vm()
        assertEquals("corrupt", v.loadError.await { it != null })
        assertTrue(v.servers.value.servers.isEmpty())
    }

    @Test fun `choosing a file records its name`() {
        val v = vm()
        v.chooseFile("b.scpbak", file)
        assertEquals(ImportState.Idle("b.scpbak", null), v.state.value)
    }

    @Test fun `decrypt without a file is an error and never calls the decryptor`() {
        val v = vm()
        v.decrypt("pw")
        assertIs<ImportState.Idle>(v.state.value)
        assertTrue((v.state.value as ImportState.Idle).error!!.contains("file", ignoreCase = true))
        assertEquals(0, dec.calls.get())
    }

    @Test fun `an empty passphrase is rejected before decrypting`() {
        val v = vm()
        v.chooseFile("b", file)
        v.decrypt("")
        assertTrue((v.state.value as ImportState.Idle).error!!.contains("passphrase", ignoreCase = true))
        assertEquals(0, dec.calls.get())
    }

    @Test fun `a file read failure is shown and keeps no file`() {
        val v = vm()
        v.fileReadFailed("too large")
        assertEquals(ImportState.Idle(null, "too large"), v.state.value)
    }

    @Test fun `the passphrase is passed through untrimmed`() = runBlocking<Unit> {
        val v = vm()
        v.chooseFile("b", file)
        v.decrypt("  pw with spaces ")
        v.state.await { it is ImportState.Preview }
        assertEquals("  pw with spaces ", dec.lastPassphrase)
    }

    @Test fun `successful decrypt previews the contents and persists nothing yet`() = runBlocking<Unit> {
        val v = vm()
        val p = v.toPreview()
        assertEquals("b.scpbak", p.fileName)
        assertEquals(listOf("A", "B"), p.contents.servers.map { it.name })
        assertEquals("https://b.example", p.contents.boundary?.controllerUrl)
        assertEquals(0, repo.replaced.get())
        assertNull(secrets.secrets.oidcPassword)
        assertFalse(p.toString().contains("pw\"") || p.toString().contains("otp"))
    }

    @Test fun `wrong passphrase keeps the file and shows the error`() = runBlocking<Unit> {
        dec.result = { throw IllegalStateException("backup: wrong passphrase or damaged backup") }
        val v = vm()
        v.chooseFile("b.scpbak", file)
        v.decrypt("nope")
        val idle = v.state.await { it is ImportState.Idle && it.error != null } as ImportState.Idle
        assertEquals("b.scpbak", idle.fileName)
        assertTrue(idle.error!!.contains("wrong passphrase"))
        assertEquals(0, repo.replaced.get())
    }

    @Test fun `a payload that decrypts but does not parse is an error`() = runBlocking<Unit> {
        dec.result = { "not json".toByteArray() }
        val v = vm()
        v.chooseFile("b", file)
        v.decrypt("pw")
        assertTrue((v.state.await { it is ImportState.Idle && it.error != null } as ImportState.Idle).error!!.isNotBlank())
    }

    @Test fun `apply replaces the list, saves the secrets and updates the flow`() = runBlocking<Unit> {
        repo.stored = StoredServers(listOf(ServerEntry("old", "Old", "h", 22, "u", null)), null)
        val v = vm()
        v.toPreview()
        v.apply()
        v.state.await { it is ImportState.Done }
        assertEquals(listOf("a", "b"), repo.stored.servers.map { it.id })
        assertEquals("pw", secrets.secrets.oidcPassword)
        assertEquals("otp", secrets.secrets.oidcOtpSecret)
        assertEquals(listOf("a", "b"), v.servers.value.servers.map { it.id })
        assertEquals(2, (v.state.value as ImportState.Done).serverCount)
    }

    @Test fun `importing a backup without secrets clears the old ones`() = runBlocking<Unit> {
        secrets.secrets = ImportedSecrets("old", "old")
        dec.result = { """{"schema":1,"files":{"servers.json":null,"config.json":null},"credentials":{}}""".toByteArray() }
        val v = vm()
        v.toPreview()
        v.apply()
        v.state.await { it is ImportState.Done }
        assertNull(secrets.secrets.oidcPassword)
        assertTrue(repo.stored.servers.isEmpty())
    }

    @Test fun `a failing write leaves the list and flow unchanged and reports the error`() = runBlocking<Unit> {
        repo.stored = StoredServers(listOf(ServerEntry("old", "Old", "h", 22, "u", null)), null)
        val v = vm()
        v.servers.await { it.servers.isNotEmpty() }
        v.toPreview()
        repo.failReplace = IOException("disk full")
        v.apply()
        val idle = v.state.await { it is ImportState.Idle && it.error != null } as ImportState.Idle
        assertTrue(idle.error!!.contains("disk full"))
        assertEquals(listOf("old"), v.servers.value.servers.map { it.id })
        assertNull(secrets.secrets.oidcPassword)
    }

    @Test fun `a failing secret save reports the error and keeps the old list`() = runBlocking<Unit> {
        repo.stored = StoredServers(listOf(ServerEntry("old", "Old", "h", 22, "u", null)), null)
        secrets.failSave = IOException("keystore unavailable")
        val v = vm()
        v.servers.await { it.servers.isNotEmpty() }
        v.toPreview()
        v.apply()
        val idle = v.state.await { it is ImportState.Idle && it.error != null } as ImportState.Idle
        assertTrue(idle.error!!.contains("keystore"))
        assertEquals(listOf("old"), repo.stored.servers.map { it.id })
        assertEquals(listOf("old"), v.servers.value.servers.map { it.id })
    }

    @Test fun `cancel in the preview drops the contents and persists nothing`() = runBlocking<Unit> {
        val v = vm()
        v.toPreview()
        v.cancel()
        assertEquals(ImportState.Idle(null, null), v.state.value)
        assertEquals(0, repo.replaced.get())
        v.apply() // nothing to apply any more
        assertEquals(0, repo.replaced.get())
    }

    @Test fun `a decrypt result that arrives after cancel is dropped`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        dec.result = { entered.countDown(); release.await(5, TimeUnit.SECONDS); PLAIN.copyOf() }
        val v = vm()
        v.chooseFile("b", file)
        v.decrypt("pw")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(ImportState.Decrypting, v.state.value)
        v.cancel()
        release.countDown()
        Thread.sleep(100)
        assertEquals(ImportState.Idle(null, null), v.state.value)
    }

    @Test fun `a second decrypt while one runs is ignored`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        dec.result = { entered.countDown(); release.await(5, TimeUnit.SECONDS); PLAIN.copyOf() }
        val v = vm()
        v.chooseFile("b", file)
        v.decrypt("pw")
        entered.await(5, TimeUnit.SECONDS)
        v.decrypt("pw")
        release.countDown()
        v.state.await { it is ImportState.Preview }
        assertEquals(1, dec.calls.get())
    }

    @Test fun `finishing returns to a clean idle state`() = runBlocking<Unit> {
        val v = vm()
        v.toPreview()
        v.apply()
        v.state.await { it is ImportState.Done }
        v.finish()
        assertEquals(ImportState.Idle(null, null), v.state.value)
    }

    @Test fun `boundary settings are kept on the stored model`() = runBlocking<Unit> {
        val v = vm()
        v.toPreview()
        v.apply()
        v.state.await { it is ImportState.Done }
        assertEquals(
            BoundarySettings("https://b.example", "amoidc_1", "oidc", "browser", ""),
            v.servers.value.boundary,
        )
    }
}
