package run.scp.mobile.app.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the decrypted backup payload (the desktop `Archive`, schema 1). Only what the phone needs is
 * extracted: servers, the shared Boundary settings, and the two Boundary OIDC secrets. The raw
 * `config.json` is never kept, because the archive carries legacy plaintext password fields in it.
 */
object BackupParser {
    const val MAX_SERVERS = 10_000
    private const val ACCOUNT_OIDC_PASSWORD = "boundary_oidc_password"
    private const val ACCOUNT_OIDC_OTP = "boundary_oidc_otp_secret"

    fun parse(plaintext: ByteArray): BackupContents {
        val root = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw BackupFormatException("The backup payload is not valid JSON")
        }
        if (root.optIntStrict("schema") != 1) throw BackupFormatException("Unsupported backup schema")
        val files = root.opt("files") as? JSONObject ?: throw BackupFormatException("The backup has no settings files")

        val warnings = mutableListOf<String>()
        return BackupContents(
            servers = parseServers(files.opt("servers.json"), warnings),
            boundary = parseBoundary(files.opt("config.json")),
            secrets = parseSecrets(root.opt("credentials")),
            warnings = warnings,
        )
    }

    private fun parseServers(file: Any?, warnings: MutableList<String>): List<ServerEntry> {
        if (file == null || file == JSONObject.NULL) return emptyList()
        val list = (file as? JSONObject)?.opt("servers") as? JSONArray
            ?: throw BackupFormatException("Invalid server list in the backup")
        if (list.length() > MAX_SERVERS) throw BackupFormatException("Too many servers in the backup")

        val seen = mutableSetOf<String>()
        val out = mutableListOf<ServerEntry>()
        for (i in 0 until list.length()) {
            val o = list.opt(i) as? JSONObject
            val problem = if (o == null) "not an object" else null
            val entry = o?.let { serverOrNull(it) }
            val label = o?.optStringStrict("name")?.ifBlank { null } ?: o?.optStringStrict("id") ?: "#${i + 1}"
            when {
                entry == null -> warnings += "Skipped server $label: ${problem ?: "missing or invalid fields"}"
                !seen.add(entry.id) -> warnings += "Skipped duplicate server id ${entry.id}"
                else -> out += entry
            }
        }
        return out
    }

    private fun serverOrNull(o: JSONObject): ServerEntry? {
        val id = o.optStringStrict("id")?.takeIf { it.isNotBlank() } ?: return null
        val host = o.optStringStrict("host")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val user = o.optStringStrict("sshUser")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = o.optIntStrict("sshPort")?.takeIf { it in 1..65535 } ?: return null
        val name = o.optStringStrict("name")?.trim().orEmpty().ifEmpty { host }
        val target = when (o.optStringStrict("connectionMethod")) {
            "ssh" -> null
            "boundary" -> o.optStringStrict("boundaryTargetId")?.trim().orEmpty()
            else -> return null
        }
        return ServerEntry(id, name, host, port, user, target)
    }

    private fun parseBoundary(file: Any?): BoundarySettings? {
        val b = (file as? JSONObject)?.opt("boundary") as? JSONObject ?: return null
        return BoundarySettings(
            controllerUrl = b.optStringStrict("addr")?.trim().orEmpty(),
            authMethodId = b.optStringStrict("authMethodId")?.trim().orEmpty(),
            authKind = b.optStringStrict("authKind") ?: "oidc",
            loginMode = b.optStringStrict("oidcLoginMode") ?: "browser",
            oidcUsername = b.optStringStrict("oidcUsername").orEmpty(),
        )
    }

    private fun parseSecrets(credentials: Any?): ImportedSecrets {
        val c = credentials as? JSONObject
        fun secret(account: String) = c?.optStringStrict(account)?.takeIf { it.isNotEmpty() }
        return ImportedSecrets(secret(ACCOUNT_OIDC_PASSWORD), secret(ACCOUNT_OIDC_OTP))
    }

    /** `org.json` coerces numbers to strings and back; this only accepts the exact JSON type. */
    private fun JSONObject.optStringStrict(key: String): String? = opt(key) as? String

    private fun JSONObject.optIntStrict(key: String): Int? = when (val v = opt(key)) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }
}
