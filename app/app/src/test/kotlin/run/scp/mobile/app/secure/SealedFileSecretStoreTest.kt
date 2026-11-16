package run.scp.mobile.app.secure

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import run.scp.mobile.app.backup.ImportedSecrets

/** Same blob layout as the Keystore sealer (12-byte IV + AES-GCM), with an in-memory key. */
internal class AesSealer(private val key: ByteArray = ByteArray(32) { it.toByte() }) : Sealer {
    override fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    override fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

class SealedFileSecretStoreTest {
    private val file = File(createTempDirectory("secrets").toFile(), "boundary_secrets.enc")

    @Test fun `missing file loads as no secrets`() {
        val s = SealedFileSecretStore(file, AesSealer()).load()
        assertNull(s.oidcPassword)
        assertNull(s.oidcOtpSecret)
    }

    @Test fun `secrets round trip`() {
        val store = SealedFileSecretStore(file, AesSealer())
        store.save(ImportedSecrets("pässword", "JBSWY3DPEHPK3PXP"))
        val s = SealedFileSecretStore(file, AesSealer()).load()
        assertEquals("pässword", s.oidcPassword)
        assertEquals("JBSWY3DPEHPK3PXP", s.oidcOtpSecret)
    }

    @Test fun `the file never contains plaintext`() {
        SealedFileSecretStore(file, AesSealer()).save(ImportedSecrets("hunter2-password", "JBSWY3DPEHPK3PXP"))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("hunter2-password") || text.contains("JBSWY3DPEHPK3PXP") || text.contains("oidcPassword"))
    }

    @Test fun `saving no secrets removes the file`() {
        val store = SealedFileSecretStore(file, AesSealer())
        store.save(ImportedSecrets("p", "o"))
        store.save(ImportedSecrets(null, null))
        assertFalse(file.exists())
        assertNull(store.load().oidcPassword)
    }

    @Test fun `partial secrets are kept as they are`() {
        val store = SealedFileSecretStore(file, AesSealer())
        store.save(ImportedSecrets("p", null))
        val s = store.load()
        assertEquals("p", s.oidcPassword)
        assertNull(s.oidcOtpSecret)
    }

    @Test fun `a file sealed with a different key loads as no secrets instead of crashing`() {
        SealedFileSecretStore(file, AesSealer()).save(ImportedSecrets("p", "o"))
        val s = SealedFileSecretStore(file, AesSealer(ByteArray(32) { 9 })).load()
        assertNull(s.oidcPassword)
    }

    @Test fun `a corrupt file loads as no secrets`() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(SealedFileSecretStore(file, AesSealer()).load().oidcPassword)
    }

    @Test fun `no temp files are left behind`() {
        SealedFileSecretStore(file, AesSealer()).save(ImportedSecrets("p", "o"))
        assertEquals(listOf(file.name), file.parentFile.list()!!.toList())
    }
}
