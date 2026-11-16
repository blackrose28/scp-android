package run.scp.mobile.app.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import run.scp.mobile.scpmobile.Scpmobile

/** Decrypts an SCP backup file to its plaintext JSON payload. Blocks for about a second (Argon2id); call off the main thread. */
interface BackupDecryptor {
    fun decrypt(data: ByteArray, passphrase: String): ByteArray
}

/** Backed by scpmobile.aar (`DecryptBackup`). Errors carry a generic message; nothing from the file or passphrase. */
class NativeBackupDecryptor : BackupDecryptor {
    override fun decrypt(data: ByteArray, passphrase: String): ByteArray = Scpmobile.decryptBackup(data, passphrase)
}

/** Backups are at most 32 MiB (the desktop's limit). */
const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

/** Reads at most [max] bytes; anything longer is rejected without reading it all into memory. */
fun readBounded(input: InputStream, max: Int = MAX_BACKUP_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw BackupFormatException("The file is too large to be a backup")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
