package run.scp.mobile.app.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.scp.mobile.app.backup.ImportedSecrets
import run.scp.mobile.app.session.Route

class LoginPlanTest {
    private val browserRoute = Route.Boundary("https://b.example", "amoidc_1", "ttcp_1")
    private val autoRoute = browserRoute.copy(automatedLogin = true, oidcUsername = "alice")

    @Test fun `browser mode uses the browser`() {
        assertEquals(LoginPlan.Browser, loginPlan(browserRoute, ImportedSecrets("pw", "SECRET")))
    }

    @Test fun `automated mode with a password and username uses the webview`() {
        val p = loginPlan(autoRoute, ImportedSecrets("pw", "GEZDGNBV"))
        assertIs<LoginPlan.Automated>(p)
        assertEquals("alice", p.username)
        assertEquals("pw", p.password)
        assertEquals("GEZDGNBV", p.otpSecret)
    }

    @Test fun `otp secret is optional`() {
        val p = loginPlan(autoRoute, ImportedSecrets("pw", null))
        assertIs<LoginPlan.Automated>(p)
        assertNull(p.otpSecret)
    }

    @Test fun `automated mode without a stored password falls back with a reason`() {
        val p = loginPlan(autoRoute, ImportedSecrets(null, "S"))
        assertIs<LoginPlan.BrowserFallback>(p)
        assertTrue(p.reason.contains("password", ignoreCase = true))
    }

    @Test fun `automated mode without a username falls back with a reason`() {
        val p = loginPlan(autoRoute.copy(oidcUsername = " "), ImportedSecrets("pw", null))
        assertIs<LoginPlan.BrowserFallback>(p)
        assertTrue(p.reason.contains("username", ignoreCase = true))
    }

    @Test fun `plans never reveal secrets in toString`() {
        val s = loginPlan(autoRoute, ImportedSecrets("hunter2", "TOPSECRETSEED")).toString()
        assertFalse(s.contains("hunter2") || s.contains("TOPSECRETSEED"), s)
    }

    // ---- where credentials may be typed ----

    private val auth = "https://idp.example/realms/x/protocol/openid-connect/auth?client_id=boundary"
    private val ctl = "https://b.example"

    @Test fun `injection is allowed on the idp and controller hosts over https`() {
        assertTrue(isInjectableUrl("https://idp.example/realms/x/login-actions/authenticate", auth, ctl))
        assertTrue(isInjectableUrl("https://IDP.example/x", auth, ctl))
        assertTrue(isInjectableUrl("https://b.example/v1/auth-methods/amoidc_1:callback", auth, ctl))
    }

    @Test fun `injection is refused for other hosts, http, null and lookalikes`() {
        assertFalse(isInjectableUrl(null, auth, ctl))
        assertFalse(isInjectableUrl("https://evil.example/login", auth, ctl))
        assertFalse(isInjectableUrl("http://idp.example/login", auth, ctl))
        assertFalse(isInjectableUrl("https://idp.example.evil.example/login", auth, ctl))
        assertFalse(isInjectableUrl("https://evil.example/?https://idp.example/", auth, ctl))
        assertFalse(isInjectableUrl("https://idp.example@evil.example/", auth, ctl))
        assertFalse(isInjectableUrl("javascript:alert(1)", auth, ctl))
        assertFalse(isInjectableUrl("about:blank", auth, ctl))
    }

    @Test fun `an http auth url is never injected into`() {
        assertFalse(isInjectableUrl("http://idp.example/login", "http://idp.example/auth", "http://idp.example"))
    }

    // ---- retry cap (a rejected password must not be re-sent forever) ----

    @Test fun `governor stops after repeated password submissions`() {
        val g = FillGovernor(maxSubmits = 2)
        assertFalse(g.exhausted)
        g.onResult("password"); assertFalse(g.exhausted)
        g.onResult(""); g.onResult("next"); assertFalse(g.exhausted)
        g.onResult("password"); assertTrue(g.exhausted)
    }

    @Test fun `governor counts otp submissions separately and parses js results`() {
        val g = FillGovernor(maxSubmits = 2)
        g.onResult("\"password\""); g.onResult("\"otp\"")
        assertFalse(g.exhausted)
        g.onResult("\"otp\"")
        assertTrue(g.exhausted)
    }

    @Test fun `governor ignores null and unknown results`() {
        val g = FillGovernor(maxSubmits = 1)
        g.onResult(null); g.onResult("null"); g.onResult("\"weird\"")
        assertFalse(g.exhausted)
    }
}
