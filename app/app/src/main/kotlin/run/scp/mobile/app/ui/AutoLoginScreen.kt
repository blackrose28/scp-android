package run.scp.mobile.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import run.scp.mobile.app.login.FillGovernor
import run.scp.mobile.app.login.LoginPlan
import run.scp.mobile.app.login.isInjectableUrl
import run.scp.mobile.app.login.oidcFillScript
import run.scp.mobile.app.login.totpCode
import run.scp.mobile.app.session.Route
import run.scp.mobile.app.session.SshTarget

/**
 * Automated Boundary OIDC login (the desktop's "automated" mode): the IdP page loads in an invisible WebView
 * and [oidcFillScript] types the stored credentials into it once a second, with a fresh TOTP each time. The
 * app itself keeps polling the controller for the token, exactly as in the browser flow.
 *
 * Credentials are only typed into https pages on the IdP or controller host ([isInjectableUrl]), the number
 * of password/OTP submissions is capped ([FillGovernor]) so a rejected login cannot be retried forever, and
 * the WebView's cookies are wiped on entry and exit. If the cap is hit, [onUseBrowser] hands over to the
 * normal browser login for the same pending login.
 */
@SuppressLint("SetJavaScriptEnabled") // the IdP login form needs it; navigation is limited to https below
@Composable
fun AutoLoginScreen(
    target: SshTarget,
    route: Route.Boundary,
    authUrl: String,
    plan: LoginPlan.Automated,
    onCancel: () -> Unit,
    onUseBrowser: (reason: String) -> Unit,
) {
    val governor = remember(authUrl) { FillGovernor() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var stage by remember(authUrl) { mutableStateOf("Opening the sign-in page…") }

    DisposableEffect(authUrl) {
        CookieManager.getInstance().removeAllCookies(null) // no stale IdP session, and none left behind
        onDispose {
            CookieManager.getInstance().removeAllCookies(null)
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(webView, authUrl) {
        val wv = webView ?: return@LaunchedEffect
        wv.loadUrl(authUrl)
        while (true) {
            delay(1_000)
            if (governor.exhausted) {
                onUseBrowser("Automatic sign-in did not complete (the identity provider kept asking). Sign in with the browser instead.")
                return@LaunchedEffect
            }
            if (!isInjectableUrl(wv.url, authUrl, route.controllerUrl)) continue
            val otp = plan.otpSecret?.let { runCatching { totpCode(it, System.currentTimeMillis() / 1000) }.getOrNull() }.orEmpty()
            wv.evaluateJavascript(oidcFillScript(plan.username, plan.password, otp)) { result ->
                governor.onResult(result)
                when (result?.trim('"')) {
                    "password" -> stage = "Signing in as ${plan.username}…"
                    "otp" -> stage = "Entering the one-time code…"
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Attached to the window and laid out at full size so the page renders and its scripts run, but not drawn.
        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(0f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    webViewClient = object : WebViewClient() {
                        // https only: the page cannot navigate to intent:, file:, javascript: or plain http.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            !request.url.scheme.equals("https", ignoreCase = true)

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit
                    }
                    webView = this
                }
            },
        )
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Signing in to Boundary automatically", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text(stage, Modifier.padding(top = 8.dp))
            Text("Then connecting to ${target.user}@${target.alias}…", Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { onUseBrowser("") }, Modifier.padding(top = 16.dp)) { Text("Use browser instead") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
