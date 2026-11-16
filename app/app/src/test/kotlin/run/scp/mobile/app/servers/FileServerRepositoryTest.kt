package run.scp.mobile.app.servers

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import run.scp.mobile.app.backup.BoundarySettings
import run.scp.mobile.app.backup.ServerEntry

class FileServerRepositoryTest {
    private val dir = createTempDirectory("servers").toFile()
    private val file = File(dir, "servers.json")
    private val repo = FileServerRepository(file)

    private val stored = StoredServers(
        servers = listOf(
            ServerEntry("srv-1", "Zażółć", "10.0.0.5", 22, "ops", null),
            ServerEntry("srv-2", "Via Boundary", "db1", 2222, "dba", "ttcp_abc1"),
        ),
        boundary = BoundarySettings("https://b.example:9200", "amoidc_1", "oidc", "automated", "alice"),
    )

    @Test fun `missing file loads as empty`() {
        assertEquals(StoredServers.EMPTY, repo.load())
        assertEquals(emptyList(), repo.load().servers)
        assertNull(repo.load().boundary)
    }

    @Test fun `replace then load round trips`() {
        repo.replace(stored)
        assertEquals(stored, FileServerRepository(file).load())
    }

    @Test fun `a null boundary round trips`() {
        repo.replace(stored.copy(boundary = null))
        assertNull(repo.load().boundary)
        assertEquals(2, repo.load().servers.size)
    }

    @Test fun `replace overwrites the previous list entirely`() {
        repo.replace(stored)
        repo.replace(StoredServers(listOf(ServerEntry("srv-9", "Only", "h", 22, "u", null)), null))
        assertEquals(listOf("srv-9"), repo.load().servers.map { it.id })
    }

    @Test fun `the file holds no secret fields`() {
        repo.replace(stored)
        val text = file.readText()
        for (forbidden in listOf("password", "otp", "Password", "Secret")) assertEquals(false, text.contains(forbidden), forbidden)
    }

    @Test fun `a corrupt file is an error, not an empty list`() {
        file.writeText("{not json")
        assertFailsWith<IOException> { repo.load() }
    }

    @Test fun `an unknown file version is an error`() {
        file.writeText("""{"version":99,"servers":[]}""")
        assertFailsWith<IOException> { repo.load() }
    }

    @Test fun `no temp files are left behind`() {
        repo.replace(stored)
        repo.replace(stored)
        assertEquals(listOf("servers.json"), dir.list()!!.toList())
    }

    @Test fun `a failed write keeps the old data`() {
        repo.replace(stored)
        val blocked = FileServerRepository(File(File(dir, "servers.json"), "nested.json")) // parent is a file
        assertFailsWith<IOException> { blocked.replace(stored) }
        assertEquals(stored, repo.load())
    }
}
