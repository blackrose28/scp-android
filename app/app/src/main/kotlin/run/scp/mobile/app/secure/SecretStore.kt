package run.scp.mobile.app.secure

import java.io.File
import java.io.IOException
import org.json.JSONObject
import run.scp.mobile.app.backup.ImportedSecrets
import run.scp.mobile.app.io.atomicWrite

/** Where the imported Boundary OIDC secrets live. */
interface SecretStore {
    fun load(): ImportedSecrets
    fun save(secrets: ImportedSecrets)
}

/**
 * One [Sealer]-encrypted file. Saving no secrets deletes the file. A file that cannot be opened (damaged,
 * or sealed under a Keystore key that no longer exists) loads as "no secrets": re-importing the backup
 * restores them, and the app never crashes on it.
 */
class SealedFileSecretStore(private val file: File, private val sealer: Sealer) : SecretStore {
    override fun load(): ImportedSecrets {
        if (!file.exists()) return ImportedSecrets(null, null)
        return try {
            val json = JSONObject(String(sealer.open(file.readBytes()), Charsets.UTF_8))
            ImportedSecrets(json.optString(PASSWORD).ifEmpty { null }, json.optString(OTP).ifEmpty { null })
        } catch (e: Exception) {
            ImportedSecrets(null, null)
        }
    }

    override fun save(secrets: ImportedSecrets) {
        if (secrets.oidcPassword == null && secrets.oidcOtpSecret == null) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove the stored secrets")
            return
        }
        val json = JSONObject()
            .put(PASSWORD, secrets.oidcPassword ?: "")
            .put(OTP, secrets.oidcOtpSecret ?: "")
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        try {
            atomicWrite(file, sealer.seal(plain))
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val PASSWORD = "oidcPassword"
        const val OTP = "oidcOtpSecret"
    }
}
