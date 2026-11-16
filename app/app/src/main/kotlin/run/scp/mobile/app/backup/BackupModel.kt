package run.scp.mobile.app.backup

import run.scp.mobile.app.session.Route
import run.scp.mobile.app.session.SshTarget
import run.scp.mobile.app.ui.boundaryRoute

/**
 * A server from `servers.json`. [boundaryTargetId] is non-null exactly for `connectionMethod: boundary`
 * (possibly blank or malformed, which makes the server unavailable rather than dropping it).
 * Desktop-only fields (`boundaryListenPort`, `services`, `os`) are intentionally not carried over.
 */
data class ServerEntry(
    val id: String,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val boundaryTargetId: String?,
)

/** The shared Boundary controller settings from `config.json`. Never includes legacy plaintext passwords. */
data class BoundarySettings(
    val controllerUrl: String,
    val authMethodId: String,
    val authKind: String,
    val loginMode: String,
    val oidcUsername: String,
)

/** Boundary OIDC secrets for the automated login. Not a data class so `toString` never reveals them. */
class ImportedSecrets(val oidcPassword: String?, val oidcOtpSecret: String?) {
    override fun toString() = "ImportedSecrets(oidcPassword=${mask(oidcPassword)}, oidcOtpSecret=${mask(oidcOtpSecret)})"
    private fun mask(s: String?) = if (s == null) "none" else "present"
}

data class BackupContents(
    val servers: List<ServerEntry>,
    val boundary: BoundarySettings?,
    val secrets: ImportedSecrets,
    /** Human-readable notes about entries that were skipped. */
    val warnings: List<String>,
)

class BackupFormatException(message: String) : Exception(message)

sealed interface ConnectTarget {
    data class Ready(val target: SshTarget) : ConnectTarget
    data class Unavailable(val reason: String) : ConnectTarget
}

/** The route to use for this server given the shared Boundary settings, or why it cannot be used. */
fun ServerEntry.connectTarget(boundary: BoundarySettings?): ConnectTarget {
    val targetId = boundaryTargetId ?: return ConnectTarget.Ready(SshTarget(host, sshPort, sshUser, Route.Direct))
    if (boundary == null || boundary.controllerUrl.isBlank() || boundary.authMethodId.isBlank()) {
        return ConnectTarget.Unavailable("Boundary controller is not configured in the backup")
    }
    if (boundary.authKind != "oidc") {
        return ConnectTarget.Unavailable("Boundary password login is not supported (OIDC only)")
    }
    val route = boundaryRoute(boundary.controllerUrl, boundary.authMethodId, targetId)
        ?: return ConnectTarget.Unavailable("Boundary settings or target ID are invalid")
    val automated = boundary.loginMode == "automated"
    return ConnectTarget.Ready(
        SshTarget(host, sshPort, sshUser, route.copy(automatedLogin = automated, oidcUsername = if (automated) boundary.oidcUsername else "")),
    )
}
