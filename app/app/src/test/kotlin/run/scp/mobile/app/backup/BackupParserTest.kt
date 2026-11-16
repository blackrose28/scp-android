package run.scp.mobile.app.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.scp.mobile.app.session.Route
import run.scp.mobile.app.session.SshTarget

private fun vector() = File("../../testdata/backup-v1.plain.json").readBytes()

private fun archive(
    servers: String? = "[]",
    boundary: String = """{"addr":"https://b.example","authMethodId":"amoidc_1","authKind":"oidc","oidcLoginMode":"browser"}""",
    credentials: String = "{}",
    schema: Int = 1,
): ByteArray {
    val serversFile = if (servers == null) "null" else """{"servers":$servers}"""
    return """{"schema":$schema,"appVersion":"t","platform":"linux","createdAtMs":1,
        "files":{"config.json":{"boundary":$boundary},"servers.json":$serversFile,"sync-local.json":null},
        "credentials":$credentials,"externalFiles":[]}""".toByteArray()
}

private fun srv(
    id: String = "srv-1", name: String = "n", host: String = "h", port: String = "22", user: String = "u",
    method: String = "ssh", target: String? = null,
): String = """{"id":"$id","name":"$name","host":"$host","connectionMethod":"$method","sshPort":$port,"sshUser":"$user"""" +
    (if (target != null) ""","boundaryTargetId":"$target"""" else "") + "}"

class BackupParserTest {
    @Test fun `real vector parses into servers, boundary settings and secrets`() {
        val c = BackupParser.parse(vector())
        assertEquals(2, c.servers.size)
        val direct = c.servers[0]
        assertEquals(ServerEntry("srv-11111111-1111-1111-1111-111111111111", "Direct box", "10.0.0.5", 22, "ops", null), direct)
        val viaBoundary = c.servers[1]
        assertEquals(2222, viaBoundary.sshPort) // concrete value: proves a real JSON parser is on the test classpath
        assertEquals("db1.internal", viaBoundary.host)
        assertEquals("ttcp_abcdef1234", viaBoundary.boundaryTargetId)
        assertEquals(
            BoundarySettings("https://boundary.example.com:9200", "amoidc_1234567890", "oidc", "automated", "alice@example.com"),
            c.boundary,
        )
        assertEquals("oidc-pw", c.secrets.oidcPassword)
        assertEquals("JBSWY3DPEHPK3PXP", c.secrets.oidcOtpSecret)
        assertTrue(c.warnings.isEmpty())
    }

    @Test fun `secrets never appear in string forms`() {
        val c = BackupParser.parse(vector())
        for (text in listOf(c.toString(), c.secrets.toString())) {
            assertFalse(text.contains("oidc-pw") || text.contains("JBSWY3DPEHPK3PXP"), text)
        }
    }

    @Test fun `unsupported schema is rejected`() {
        assertFailsWith<BackupFormatException> { BackupParser.parse(archive(schema = 2)) }
        assertFailsWith<BackupFormatException> { BackupParser.parse("""{"files":{}}""".toByteArray()) }
    }

    @Test fun `garbage and wrong shapes are rejected`() {
        for (bad in listOf("", "not json", "[]", """{"schema":1}""", """{"schema":1,"files":[]}""")) {
            assertFailsWith<BackupFormatException>(bad) { BackupParser.parse(bad.toByteArray()) }
        }
        assertFailsWith<BackupFormatException> {
            BackupParser.parse("""{"schema":1,"files":{"servers.json":{"servers":"x"}}}""".toByteArray())
        }
    }

    @Test fun `missing or null servers file and config give empty results`() {
        assertTrue(BackupParser.parse(archive(servers = null)).servers.isEmpty())
        val noConfig = """{"schema":1,"files":{"servers.json":null,"config.json":null},"credentials":{}}""".toByteArray()
        val c = BackupParser.parse(noConfig)
        assertNull(c.boundary)
        assertNull(c.secrets.oidcPassword)
    }

    @Test fun `null and blank credentials become null`() {
        val c = BackupParser.parse(archive(credentials = """{"boundary_oidc_password":null,"boundary_oidc_otp_secret":""}"""))
        assertNull(c.secrets.oidcPassword)
        assertNull(c.secrets.oidcOtpSecret)
    }

    @Test fun `only the oidc secrets are carried over`() {
        val c = BackupParser.parse(
            archive(credentials = """{"openvpn_password":"vpn","boundary_app_token":"tok","boundary_oidc_password":"p"}"""),
        )
        assertEquals("p", c.secrets.oidcPassword)
        assertFalse(c.toString().contains("vpn") || c.toString().contains("tok"))
    }

    @Test fun `legacy plaintext secrets in config json are never read`() {
        val c = BackupParser.parse(
            archive(boundary = """{"addr":"https://b.example","authMethodId":"amoidc_1","oidcPassword":"legacy","password":"legacy2"}"""),
        )
        assertNull(c.secrets.oidcPassword)
        assertEquals("https://b.example", c.boundary?.controllerUrl)
    }

    @Test fun `boundary defaults are oidc and browser`() {
        val c = BackupParser.parse(archive(boundary = """{"addr":"https://b.example","authMethodId":"amoidc_1"}"""))
        assertEquals("oidc", c.boundary?.authKind)
        assertEquals("browser", c.boundary?.loginMode)
        assertEquals("", c.boundary?.oidcUsername)
    }

    @Test fun `malformed servers are skipped with a warning and the rest import`() {
        val list = "[" + listOf(
            srv(id = "ok"),
            srv(id = "nohost", host = ""),
            srv(id = "port0", port = "0"),
            srv(id = "port70000", port = "70000"),
            srv(id = "portstr", port = "\"22\""),
            srv(id = "nouser", user = " "),
            srv(id = "badmethod", method = "telnet"),
            """{"name":"noid","host":"h","connectionMethod":"ssh","sshPort":22,"sshUser":"u"}""",
        ).joinToString(",") + "]"
        val c = BackupParser.parse(archive(servers = list))
        assertEquals(listOf("ok"), c.servers.map { it.id })
        assertEquals(7, c.warnings.size)
    }

    @Test fun `duplicate ids keep the first and warn`() {
        val c = BackupParser.parse(archive(servers = "[" + srv(id = "a", name = "first") + "," + srv(id = "a", name = "second") + "]"))
        assertEquals(listOf("first"), c.servers.map { it.name })
        assertEquals(1, c.warnings.size)
    }

    @Test fun `a blank name falls back to the host`() {
        assertEquals("h", BackupParser.parse(archive(servers = "[" + srv(name = "") + "]")).servers.single().name)
    }

    @Test fun `server count is capped`() {
        val many = (1..10_001).joinToString(",", "[", "]") { srv(id = "s$it") }
        assertFailsWith<BackupFormatException> { BackupParser.parse(archive(servers = many)) }
    }

    // ---- mapping to a connectable target ----

    private val boundary = BoundarySettings("https://b.example", "amoidc_1", "oidc", "browser", "")

    @Test fun `ssh server maps to a direct target`() {
        val t = ServerEntry("i", "n", "10.0.0.5", 22, "ops", null).connectTarget(null)
        assertEquals(ConnectTarget.Ready(SshTarget("10.0.0.5", 22, "ops", Route.Direct)), t)
    }

    @Test fun `boundary server maps to the shared controller and its own target`() {
        val t = ServerEntry("i", "n", "db", 2222, "dba", "ttcp_abc1").connectTarget(boundary)
        assertEquals(
            ConnectTarget.Ready(SshTarget("db", 2222, "dba", Route.Boundary("https://b.example", "amoidc_1", "ttcp_abc1"))),
            t,
        )
    }

    @Test fun `boundary server is unavailable without controller settings`() {
        assertIs<ConnectTarget.Unavailable>(ServerEntry("i", "n", "db", 22, "u", "ttcp_abc1").connectTarget(null))
        assertIs<ConnectTarget.Unavailable>(ServerEntry("i", "n", "db", 22, "u", "ttcp_abc1").connectTarget(boundary.copy(controllerUrl = "")))
    }

    @Test fun `boundary server is unavailable with password auth`() {
        val t = ServerEntry("i", "n", "db", 22, "u", "ttcp_abc1").connectTarget(boundary.copy(authKind = "password"))
        assertTrue((t as ConnectTarget.Unavailable).reason.contains("password", ignoreCase = true))
    }

    @Test fun `boundary server is unavailable with a malformed target id`() {
        assertIs<ConnectTarget.Unavailable>(ServerEntry("i", "n", "db", 22, "u", "").connectTarget(boundary))
        assertIs<ConnectTarget.Unavailable>(ServerEntry("i", "n", "db", 22, "u", "bogus").connectTarget(boundary))
    }

    @Test fun `automated login mode still connects through the browser`() {
        val t = ServerEntry("i", "n", "db", 22, "u", "ttcp_abc1").connectTarget(boundary.copy(loginMode = "automated"))
        assertIs<ConnectTarget.Ready>(t)
    }
}
