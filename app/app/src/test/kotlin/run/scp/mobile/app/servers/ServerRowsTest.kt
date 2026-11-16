package run.scp.mobile.app.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import run.scp.mobile.app.backup.BoundarySettings
import run.scp.mobile.app.backup.ConnectTarget
import run.scp.mobile.app.backup.ServerEntry
import run.scp.mobile.app.session.Route

class ServerRowsTest {
    private val boundary = BoundarySettings("https://b.example", "amoidc_1", "oidc", "browser", "")

    @Test fun `rows keep the backup order and describe each server`() {
        val rows = serverRows(
            StoredServers(
                listOf(
                    ServerEntry("1", "Zeta", "10.0.0.5", 22, "ops", null),
                    ServerEntry("2", "Alpha", "db1", 2222, "dba", "ttcp_abc1"),
                ),
                boundary,
            ),
        )
        assertEquals(listOf("Zeta", "Alpha"), rows.map { it.title })
        assertEquals("ops@10.0.0.5:22 · Direct", rows[0].subtitle)
        assertEquals("dba@db1:2222 · via Boundary", rows[1].subtitle)
        assertEquals(Route.Direct, (rows[0].target as ConnectTarget.Ready).target.route)
        assertEquals(Route.Boundary("https://b.example", "amoidc_1", "ttcp_abc1"), (rows[1].target as ConnectTarget.Ready).target.route)
    }

    @Test fun `unavailable servers stay in the list with a reason`() {
        val rows = serverRows(StoredServers(listOf(ServerEntry("2", "B", "db1", 22, "u", "ttcp_abc1")), null))
        val t = rows.single().target
        assertIs<ConnectTarget.Unavailable>(t)
        assertEquals("u@db1:22 · via Boundary", rows.single().subtitle)
    }

    @Test fun `ids are carried for list keys`() {
        assertEquals("1", serverRows(StoredServers(listOf(ServerEntry("1", "n", "h", 22, "u", null)), null)).single().id)
        assertNull(serverRows(StoredServers.EMPTY).firstOrNull())
    }
}
