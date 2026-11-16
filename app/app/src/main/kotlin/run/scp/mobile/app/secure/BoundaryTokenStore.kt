package run.scp.mobile.app.secure

import java.io.File
import java.io.IOException
import org.json.JSONObject
import run.scp.mobile.app.io.atomicWrite
import run.scp.mobile.app.session.BoundaryToken

/** Where Boundary auth tokens survive a process restart, keyed by controller + auth method. */
interface BoundaryTokenStore {
    /** The stored token, or null when there is none or it cannot be read. Never throws. */
    fun load(key: String): BoundaryToken?

    /** Throws if the token could not be stored. */
    fun save(key: String, token: BoundaryToken)

    /** Forgets [key]. Throws if the change could not be written. */
    fun remove(key: String)
}

/**
 * One [Sealer]-encrypted file holding every key's token (keys included, since they name the controller).
 * Removing the last token deletes the file. A file that cannot be opened (damaged, or sealed under a Keystore
 * key that no longer exists) reads as "no tokens" and is replaced on the next save: the worst case is one
 * more login, and the app never crashes on it.
 */
class SealedFileBoundaryTokenStore(private val file: File, private val sealer: Sealer) : BoundaryTokenStore {
    @Synchronized
    override fun load(key: String): BoundaryToken? = read()[key]

    @Synchronized
    override fun save(key: String, token: BoundaryToken) {
        write(read().apply { put(key, token) })
    }

    @Synchronized
    override fun remove(key: String) {
        val all = read()
        if (all.remove(key) != null) write(all)
    }

    private fun read(): MutableMap<String, BoundaryToken> {
        val out = mutableMapOf<String, BoundaryToken>()
        if (!file.exists()) return out
        try {
            val json = JSONObject(String(sealer.open(file.readBytes()), Charsets.UTF_8))
            for (k in json.keys()) {
                val e = json.getJSONObject(k)
                out[k] = BoundaryToken(e.getString(VALUE), e.getLong(EXPIRES))
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }

    private fun write(all: Map<String, BoundaryToken>) {
        if (all.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove the stored Boundary tokens")
            return
        }
        val json = JSONObject()
        for ((k, t) in all) json.put(k, JSONObject().put(VALUE, t.value).put(EXPIRES, t.expirationMs))
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        try {
            atomicWrite(file, sealer.seal(plain))
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val VALUE = "token"
        const val EXPIRES = "expirationMs"
    }
}
