package run.scp.mobile.app.ui

import java.net.URI
import run.scp.mobile.app.session.Route

private val AUTH_METHOD_ID = Regex("am[a-z]*_[A-Za-z0-9]+")
private val TARGET_ID = Regex("t[a-z]+_[A-Za-z0-9]+")

private fun hasWebScheme(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
}

/** The Boundary route for the connect form's fields, or null if any of them is malformed. */
fun boundaryRoute(controllerUrl: String, authMethodId: String, targetId: String): Route.Boundary? {
    val url = controllerUrl.trim()
    val method = authMethodId.trim()
    val target = targetId.trim()
    if (!hasWebScheme(url) || !AUTH_METHOD_ID.matches(method) || !TARGET_ID.matches(target)) return null
    return Route.Boundary(url, method, target)
}

/** The IdP URL comes from the controller; never hand anything but a web URL to the browser intent. */
fun isOpenableAuthUrl(url: String): Boolean = hasWebScheme(url)
