package run.scp.mobile.app.ssh

import run.scp.mobile.app.session.BoundaryAuthenticator
import run.scp.mobile.app.session.BoundaryLogin
import run.scp.mobile.app.session.BoundaryToken
import run.scp.mobile.scpmobile.Scpmobile

/** Boundary OIDC through scpmobile.aar (`StartOidc` / `Poll`). Both calls block on HTTP; call off the main thread. */
class NativeBoundaryAuthenticator : BoundaryAuthenticator {
    override suspend fun start(controllerUrl: String, authMethodId: String): BoundaryLogin {
        val pending = Scpmobile.startOidc(controllerUrl, authMethodId)
        return object : BoundaryLogin {
            override val authUrl: String = pending.authURL()

            override suspend fun poll(): BoundaryToken? {
                val r = pending.poll()
                return if (r.done()) BoundaryToken(r.token(), r.expirationUnixMs()) else null
            }
        }
    }
}
