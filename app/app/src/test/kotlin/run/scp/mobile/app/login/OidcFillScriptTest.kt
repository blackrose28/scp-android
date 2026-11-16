package run.scp.mobile.app.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OidcFillScriptTest {
    @Test fun `values are embedded as quoted JS strings`() {
        val s = oidcFillScript("alice", "pw", "123456")
        assertTrue(s.contains("\"alice\"") && s.contains("\"pw\"") && s.contains("\"123456\""))
    }

    @Test fun `quotes, backslashes and newlines cannot break out of the string`() {
        assertEquals("\"a\\\"b\"", jsString("a\"b"))
        assertEquals("\"a\\\\b\"", jsString("a\\b"))
        assertEquals("\"a\\nb\\r\"", jsString("a\nb\r"))
        assertEquals("\"\\u2028\\u2029\"", jsString("\u2028\u2029"))
    }

    @Test fun `html-significant characters are escaped`() {
        val q = jsString("</script><!--'&")
        assertFalse(q.contains("<") || q.contains(">") || q.contains("'") || q.contains("&"), q)
        assertEquals("\"\\u003c/script\\u003e\\u003c!--\\u0027\\u0026\"", q)
    }

    @Test fun `hostile password stays inside its literal`() {
        val s = oidcFillScript("u", "\";alert(1);//", "")
        assertTrue(s.contains("password:\"\\\";alert(1);//\""), "password must appear as one escaped literal")
    }

    @Test fun `script defines no timers and is safe to run repeatedly`() {
        val s = oidcFillScript("u", "p", "")
        assertFalse(s.contains("setInterval") || s.contains("setTimeout"))
    }
}
