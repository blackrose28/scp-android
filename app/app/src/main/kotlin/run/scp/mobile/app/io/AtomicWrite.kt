package run.scp.mobile.app.io

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes [bytes] to [target] via a temp file in the same directory, so readers see the old or the new file, never a torn one. */
fun atomicWrite(target: File, bytes: ByteArray) {
    val dir = target.parentFile ?: throw IOException("no parent directory for ${target.name}")
    val tmp = File.createTempFile(target.name, ".tmp", dir)
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        tmp.delete()
        throw if (e is IOException) e else IOException(e.message, e)
    }
}
