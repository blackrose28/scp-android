package run.scp.mobile.app.servers

import run.scp.mobile.app.backup.ConnectTarget
import run.scp.mobile.app.backup.connectTarget

/** What the server list shows for one server. */
data class ServerRow(val id: String, val title: String, val subtitle: String, val target: ConnectTarget)

fun serverRows(stored: StoredServers): List<ServerRow> = stored.servers.map { s ->
    val route = if (s.boundaryTargetId != null) "via Boundary" else "Direct"
    ServerRow(s.id, s.name, "${s.sshUser}@${s.host}:${s.sshPort} · $route", s.connectTarget(stored.boundary))
}
