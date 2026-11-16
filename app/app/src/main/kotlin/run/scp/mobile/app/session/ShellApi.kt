package run.scp.mobile.app.session

import run.scp.mobile.app.tofu.HostKeyDecider

/** How the TCP transport to the server is obtained. */
sealed interface Route {
    /** TCP straight to host:port (over the VPN). */
    data object Direct : Route

    /** A Boundary session on [targetId] (`ttcp_…`), authenticated by OIDC against [authMethodId] (`amoidc_…`). */
    data class Boundary(val controllerUrl: String, val authMethodId: String, val targetId: String) : Route
}

/**
 * [host]:[port] is the server's real SSH address on both routes; it is the host-key trust
 * alias even when the bytes travel through Boundary, so one trusted key covers both routes.
 */
data class SshTarget(val host: String, val port: Int, val user: String, val route: Route = Route.Direct) {
    /** Key under which the host key is trusted: the server's real host:port. */
    val alias: String get() = "$host:$port"
}

/** A Boundary auth token. Deliberately not a data class: [toString] never reveals the secret. */
class BoundaryToken(val value: String, val expirationMs: Long) {
    override fun toString() = "BoundaryToken(expirationMs=$expirationMs)"
    override fun equals(other: Any?) = other is BoundaryToken && other.value == value && other.expirationMs == expirationMs
    override fun hashCode() = 31 * value.hashCode() + expirationMs.hashCode()
}

/** Starts Boundary OIDC logins. Blocking calls are made off the main thread. */
interface BoundaryAuthenticator {
    suspend fun start(controllerUrl: String, authMethodId: String): BoundaryLogin
}

/** An in-progress OIDC login: the user finishes it in a browser at [authUrl] while the app polls. */
interface BoundaryLogin {
    val authUrl: String

    /** The token once the login is complete, `null` while the user has not finished. */
    suspend fun poll(): BoundaryToken?
}

/** Callbacks from the shell. Invoked on a native thread; [onClosed] fires at most once. */
interface ShellEvents {
    fun onOutput(bytes: ByteArray)
    fun onClosed(reason: String)
}

/** An open PTY shell. Methods may block on the network; call them off the main thread. */
interface ShellSession {
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}

/**
 * Opens a shell, consulting [hostKeys] during the handshake. Blocking; runs off the main thread.
 * [boundaryToken] is required for [Route.Boundary] targets and ignored otherwise; it is passed
 * separately so it never lives in [SshTarget] or session state.
 */
interface ShellConnector {
    suspend fun open(
        target: SshTarget,
        cols: Int,
        rows: Int,
        hostKeys: HostKeyDecider,
        events: ShellEvents,
        boundaryToken: BoundaryToken? = null,
    ): ShellSession
}
