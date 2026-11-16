package run.scp.mobile.app.servers

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import run.scp.mobile.app.backup.BoundarySettings
import run.scp.mobile.app.backup.ServerEntry
import run.scp.mobile.app.io.atomicWrite

/** The server list and the shared Boundary settings. Holds no secrets. */
data class StoredServers(val servers: List<ServerEntry>, val boundary: BoundarySettings?) {
    companion object {
        val EMPTY = StoredServers(emptyList(), null)
    }
}

interface ServerRepository {
    /** Empty when nothing was imported yet; throws [IOException] if the stored data is unreadable. */
    fun load(): StoredServers

    /** Replaces everything. Throws [IOException] and leaves the previous data intact on failure. */
    fun replace(servers: StoredServers)
}

/** The phone's own schema (not the backup's JSON), written atomically in app-private storage. */
class FileServerRepository(private val file: File) : ServerRepository {
    override fun load(): StoredServers {
        if (!file.exists()) return StoredServers.EMPTY
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != VERSION) throw IOException("Unsupported server list version")
            val boundary = root.optJSONObject("boundary")?.let {
                BoundarySettings(
                    it.getString("controllerUrl"), it.getString("authMethodId"), it.getString("authKind"),
                    it.getString("loginMode"), it.getString("oidcUsername"),
                )
            }
            val list = root.getJSONArray("servers")
            val servers = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                ServerEntry(
                    o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("sshPort"),
                    o.getString("sshUser"), if (o.isNull("boundaryTargetId")) null else o.getString("boundaryTargetId"),
                )
            }
            return StoredServers(servers, boundary)
        } catch (e: JSONException) {
            throw IOException("The stored server list is damaged", e)
        }
    }

    override fun replace(servers: StoredServers) {
        val root = JSONObject().put("version", VERSION)
        servers.boundary?.let {
            root.put(
                "boundary",
                JSONObject().put("controllerUrl", it.controllerUrl).put("authMethodId", it.authMethodId)
                    .put("authKind", it.authKind).put("loginMode", it.loginMode).put("oidcUsername", it.oidcUsername),
            )
        }
        val list = JSONArray()
        for (s in servers.servers) {
            list.put(
                JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("sshPort", s.sshPort)
                    .put("sshUser", s.sshUser).put("boundaryTargetId", s.boundaryTargetId ?: JSONObject.NULL),
            )
        }
        root.put("servers", list)
        atomicWrite(file, root.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val VERSION = 1
    }
}
