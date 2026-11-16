package run.scp.mobile.app.login

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP as the desktop app generates it (`generate_totp_code_at_epoch`: SHA-1, 6 digits, 30 s
 * step, base32 secret). Case, spaces, dashes and `=` padding in [secret] are ignored. Errors never
 * include the secret. The desktop also corrects for clock skew via an HTTP `Date` header; this does not.
 */
fun totpCode(secret: String, epochSeconds: Long): String {
    val key = base32Decode(secret)
    val counter = ByteBuffer.allocate(8).putLong(epochSeconds / STEP_SECONDS).array()
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    val h = mac.doFinal(counter)
    key.fill(0)
    val o = h[h.size - 1].toInt() and 0x0f
    val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
        ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
    return (bin % 1_000_000).toString().padStart(6, '0')
}

private const val STEP_SECONDS = 30
private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun base32Decode(secret: String): ByteArray {
    val clean = secret.filterNot { it.isWhitespace() || it == '-' || it == '=' }.uppercase()
    require(clean.isNotEmpty()) { "OTP secret is empty" }
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in clean) {
        val v = ALPHABET.indexOf(c)
        require(v >= 0) { "OTP secret is not valid base32" }
        buffer = (buffer shl 5) or v
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xff)
        }
    }
    require(out.size() > 0) { "OTP secret is too short" }
    return out.toByteArray()
}
