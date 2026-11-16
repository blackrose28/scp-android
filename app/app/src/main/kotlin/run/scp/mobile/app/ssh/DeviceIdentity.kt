package run.scp.mobile.app.ssh

import android.content.Context
import java.io.File
import run.scp.mobile.app.secure.KeystoreSealer
import run.scp.mobile.app.secure.Sealer
import run.scp.mobile.scpmobile.Scpmobile

/** The device's SSH identity. The public half is what an admin adds to the server's authorized_keys. */
interface DeviceIdentity {
    fun privateKeyPem(): ByteArray
    fun publicAuthorizedKey(): String
}

/**
 * Generates an Ed25519 key on first use. The private key is stored only encrypted
 * (AES-256-GCM) under a non-exportable Android Keystore key; the public line is stored plain.
 */
class KeystoreDeviceIdentity(
    context: Context,
    private val sealer: Sealer = KeystoreSealer(ALIAS),
) : DeviceIdentity {
    private val dir = context.filesDir
    private val privFile = File(dir, "device_key.enc")
    private val pubFile = File(dir, "device_key.pub")
    private val lock = Any()

    override fun privateKeyPem(): ByteArray = synchronized(lock) {
        ensure()
        sealer.open(privFile.readBytes())
    }

    override fun publicAuthorizedKey(): String = synchronized(lock) {
        ensure()
        pubFile.readText()
    }

    private fun ensure() {
        if (privFile.exists() && pubFile.exists()) return
        val pair = Scpmobile.generateEd25519()
        // Write the private key first; a missing .pub means "regenerate", never a mismatched pair.
        pubFile.delete()
        privFile.writeBytes(sealer.seal(pair.privateOpenSSH()))
        pubFile.writeText(String(pair.publicAuthorizedKey(), Charsets.UTF_8))
    }

    private companion object {
        // Unchanged since the first release: existing devices' keys are sealed under this alias.
        const val ALIAS = "scp-mobile-device-key-wrap"
    }
}
