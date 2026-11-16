package run.scp.mobile.app.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small blobs for storage in app-private files. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key named [alias], created on first use.
 * Blob layout: 12-byte IV followed by ciphertext and tag.
 */
class KeystoreSealer(private val alias: String) : Sealer {
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return c.iv + c.doFinal(plain) // Keystore generates a fresh 12-byte IV
    }

    override fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        }
        return c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
