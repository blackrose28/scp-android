package run.scp.mobile.app.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import run.scp.mobile.app.session.BoundaryToken
import run.scp.mobile.app.session.Route
import run.scp.mobile.app.session.ShellEvents
import run.scp.mobile.app.session.ShellSession
import run.scp.mobile.app.session.SshTarget
import run.scp.mobile.app.tofu.HostKeyDecider

private class Recorder : ScpBackend<String> {
    val log = mutableListOf<String>()
    var openShellError: Throwable? = null
    var dialBoundaryError: Throwable? = null
    lateinit var hostKeys: HostKeyDecider

    override fun dialDirect(host: String, port: Int): String {
        log += "dialDirect $host:$port"
        return "direct-conn"
    }

    override fun dialBoundary(controllerUrl: String, token: String, targetId: String): BoundaryDial<String> {
        log += "dialBoundary $controllerUrl $token $targetId"
        dialBoundaryError?.let { throw it }
        return BoundaryDial("boundary-conn") { log += "boundary.close" }
    }

    override fun openShell(
        conn: String, user: String, privateKey: ByteArray, alias: String,
        hostKeys: HostKeyDecider, events: ShellEvents, cols: Int, rows: Int,
    ): ShellSession {
        log += "openShell $conn $user ${String(privateKey)} $alias ${cols}x$rows"
        this.hostKeys = hostKeys
        openShellError?.let { throw it }
        return object : ShellSession {
            override fun write(bytes: ByteArray) { log += "write" }
            override fun resize(cols: Int, rows: Int) { log += "resize ${cols}x$rows" }
            override fun close() { log += "shell.close" }
        }
    }
}

private val identity = object : DeviceIdentity {
    override fun privateKeyPem() = "KEY".toByteArray()
    override fun publicAuthorizedKey() = "pub"
}

private val noEvents = object : ShellEvents {
    override fun onOutput(bytes: ByteArray) = Unit
    override fun onClosed(reason: String) = Unit
}

private val trustAll = HostKeyDecider { _, _, _ -> true }

class BackendShellConnectorTest {
    private val boundary = Route.Boundary("https://b.example", "amoidc_x", "ttcp_1")
    private val token = BoundaryToken("tok", 0)

    private fun connector(b: Recorder) = BackendShellConnector(identity, b)

    @Test fun `direct route dials the host and uses its alias`() = runBlocking<Unit> {
        val b = Recorder()
        connector(b).open(SshTarget("10.0.0.5", 22, "ops"), 80, 24, trustAll, noEvents)
        assertEquals(listOf("dialDirect 10.0.0.5:22", "openShell direct-conn ops KEY 10.0.0.5:22 80x24"), b.log)
    }

    @Test fun `boundary route dials the target and keeps the real host port as alias`() = runBlocking<Unit> {
        val b = Recorder()
        connector(b).open(SshTarget("10.0.0.5", 22, "ops", boundary), 80, 24, trustAll, noEvents, token)
        assertEquals(
            listOf("dialBoundary https://b.example tok ttcp_1", "openShell boundary-conn ops KEY 10.0.0.5:22 80x24"),
            b.log,
        )
    }

    @Test fun `boundary route without a token is refused before any dial`() {
        val b = Recorder()
        assertFailsWith<IllegalArgumentException> {
            runBlocking { connector(b).open(SshTarget("h", 22, "u", boundary), 80, 24, trustAll, noEvents, null) }
        }
        assertTrue(b.log.isEmpty())
    }

    @Test fun `the host key decider is handed to the handshake`() = runBlocking<Unit> {
        val b = Recorder()
        connector(b).open(SshTarget("h", 22, "u"), 80, 24, trustAll, noEvents)
        assertTrue(b.hostKeys.decide("h:22", "ssh-ed25519", "SHA256:x"))
    }

    @Test fun `if the shell fails to open the boundary session is closed`() {
        val b = Recorder().apply { openShellError = IllegalStateException("host key rejected") }
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { connector(b).open(SshTarget("h", 22, "u", boundary), 80, 24, trustAll, noEvents, token) }
        }
        assertEquals("host key rejected", e.message)
        assertEquals(1, b.log.count { it == "boundary.close" })
    }

    @Test fun `a failed boundary dial propagates and opens no shell`() {
        val b = Recorder().apply { dialBoundaryError = java.io.IOException("authorize session: 403") }
        val e = assertFailsWith<java.io.IOException> {
            runBlocking { connector(b).open(SshTarget("h", 22, "u", boundary), 80, 24, trustAll, noEvents, token) }
        }
        assertEquals("authorize session: 403", e.message)
        assertTrue(b.log.none { it.startsWith("openShell") })
    }

    @Test fun `closing a boundary shell closes the shell then the session exactly once`() = runBlocking<Unit> {
        val b = Recorder()
        val s = connector(b).open(SshTarget("h", 22, "u", boundary), 80, 24, trustAll, noEvents, token)
        s.write("x".toByteArray()); s.resize(100, 40)
        s.close(); s.close()
        assertEquals(listOf("write", "resize 100x40", "shell.close", "boundary.close"), b.log.drop(2))
    }

    @Test fun `a boundary session is closed even when closing the shell throws`() = runBlocking<Unit> {
        val b = object : ScpBackend<String> by Recorder() {
            val closed = java.util.concurrent.atomic.AtomicInteger()
            override fun dialBoundary(controllerUrl: String, token: String, targetId: String) =
                BoundaryDial("c") { closed.incrementAndGet() }
            override fun openShell(
                conn: String, user: String, privateKey: ByteArray, alias: String,
                hostKeys: HostKeyDecider, events: ShellEvents, cols: Int, rows: Int,
            ) = object : ShellSession {
                override fun write(bytes: ByteArray) = Unit
                override fun resize(cols: Int, rows: Int) = Unit
                override fun close() = error("shell close failed")
            }
        }
        val s = BackendShellConnector(identity, b).open(SshTarget("h", 22, "u", boundary), 80, 24, trustAll, noEvents, token)
        assertFailsWith<IllegalStateException> { s.close() }
        assertEquals(1, b.closed.get())
    }

    @Test fun `direct shell close does not touch any boundary session`() = runBlocking<Unit> {
        val b = Recorder()
        val s = connector(b).open(SshTarget("h", 22, "u"), 80, 24, trustAll, noEvents)
        s.close()
        assertTrue(b.log.none { it.startsWith("boundary") })
        assertEquals("shell.close", b.log.last())
    }
}
