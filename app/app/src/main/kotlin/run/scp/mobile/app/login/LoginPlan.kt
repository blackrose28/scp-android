package run.scp.mobile.app.login

import java.net.URI
import run.scp.mobile.app.backup.ImportedSecrets
import run.scp.mobile.app.session.Route

/** How a Boundary OIDC login is carried out. */
sealed interface LoginPlan {
    /** The user signs in through the system browser. */
    data object Browser : LoginPlan

    /** Automated login was requested but cannot run; the browser is used instead, for [reason]. */
    data class BrowserFallback(val reason: String) : LoginPlan

    /** Hidden WebView login. Not a data class so `toString` never reveals the credentials. */
    class Automated(val username: String, val password: String, val otpSecret: String?) : LoginPlan {
        override fun toString() = "Automated(username=$username, password=present, otpSecret=${if (otpSecret == null) "none" else "present"})"
    }
}

fun loginPlan(route: Route.Boundary, secrets: ImportedSecrets): LoginPlan {
    if (!route.automatedLogin) return LoginPlan.Browser
    val password = secrets.oidcPassword
    return when {
        route.oidcUsername.isBlank() -> LoginPlan.BrowserFallback("Automated login needs an OIDC username, and the backup has none.")
        password.isNullOrEmpty() -> LoginPlan.BrowserFallback("Automated login needs the OIDC password, which is not stored. Import the backup again.")
        else -> LoginPlan.Automated(route.oidcUsername.trim(), password, secrets.oidcOtpSecret?.takeIf { it.isNotBlank() })
    }
}

/**
 * Credentials are typed only into https pages on the IdP's host (the host of the auth URL) or the
 * controller's host, never into whatever page a redirect happens to land on.
 */
fun isInjectableUrl(pageUrl: String?, authUrl: String, controllerUrl: String): Boolean {
    val page = hostOf(pageUrl, requireHttps = true) ?: return false
    val allowed = setOfNotNull(hostOf(authUrl, requireHttps = true), hostOf(controllerUrl, requireHttps = true))
    return page in allowed
}

private fun hostOf(url: String?, requireHttps: Boolean): String? {
    if (url == null) return null
    val uri = try { URI(url.trim()) } catch (e: Exception) { return null }
    if (requireHttps && !"https".equals(uri.scheme, ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    return uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
}

/**
 * Caps how many times the script may submit the password or the OTP. A rejected credential makes the IdP
 * show the same form again, which would otherwise be filled and submitted forever (and lock the account).
 */
class FillGovernor(private val maxSubmits: Int = 2) {
    private var passwords = 0
    private var otps = 0

    val exhausted: Boolean get() = passwords >= maxSubmits || otps >= maxSubmits

    /** [result] is the raw `evaluateJavascript` result (a JSON string literal) or null. */
    fun onResult(result: String?) {
        when (result?.trim()?.trim('"')) {
            "password" -> passwords++
            "otp" -> otps++
        }
    }
}
