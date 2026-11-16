package run.scp.mobile.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.scp.mobile.app.session.Route

class ConnectFormTest {
    @Test fun `valid boundary input becomes a route with trimmed fields`() {
        assertEquals(
            Route.Boundary("https://boundary.example:9200", "amoidc_1234567890", "ttcp_abcdef1234"),
            boundaryRoute(" https://boundary.example:9200 ", " amoidc_1234567890 ", " ttcp_abcdef1234 "),
        )
    }

    @Test fun `plain http controllers are accepted for dev setups`() {
        assertEquals("http://127.0.0.1:9200", boundaryRoute("http://127.0.0.1:9200", "amoidc_1", "ttcp_1")?.controllerUrl)
    }

    @Test fun `controller must be an http or https url with a host`() {
        for (bad in listOf("", "boundary.example", "ftp://boundary.example", "https://", "file:///etc/passwd", "javascript:alert(1)")) {
            assertNull(boundaryRoute(bad, "amoidc_1", "ttcp_1"), bad)
        }
    }

    @Test fun `ids must look like boundary ids`() {
        assertNull(boundaryRoute("https://b.example", "", "ttcp_1"))
        assertNull(boundaryRoute("https://b.example", "amoidc_1", ""))
        assertNull(boundaryRoute("https://b.example", "amoidc_1/../x", "ttcp_1"))
        assertNull(boundaryRoute("https://b.example", "amoidc 1", "ttcp_1"))
        assertNull(boundaryRoute("https://b.example", "amoidc_1", "ttcp_1 extra"))
        assertNull(boundaryRoute("https://b.example", "notanauthmethod", "ttcp_1"))
        assertNull(boundaryRoute("https://b.example", "amoidc_1", "notatarget"))
    }

    @Test fun `only http and https auth urls are opened in a browser`() {
        assertTrue(isOpenableAuthUrl("https://idp.example/auth?x=1"))
        assertTrue(isOpenableAuthUrl("http://localhost:5556/auth"))
        for (bad in listOf("", "intent://x#Intent;end", "javascript:alert(1)", "file:///sdcard/x", "content://x", "https://", "not a url")) {
            assertFalse(isOpenableAuthUrl(bad), bad)
        }
    }
}
