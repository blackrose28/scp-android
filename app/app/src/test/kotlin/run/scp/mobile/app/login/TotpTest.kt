package run.scp.mobile.app.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TotpTest {
    // RFC 6238 appendix B, SHA-1 seed "12345678901234567890" (base32 below), 6-digit truncation.
    private val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun `matches the RFC 6238 vectors`() {
        assertEquals("287082", totpCode(seed, 59))
        assertEquals("081804", totpCode(seed, 1111111109))
        assertEquals("005924", totpCode(seed, 1234567890))
        assertEquals("279037", totpCode(seed, 2000000000))
    }

    @Test fun `code is stable inside a 30 second step and changes at the boundary`() {
        assertEquals(totpCode(seed, 30), totpCode(seed, 59))
        assert(totpCode(seed, 59) != totpCode(seed, 60))
    }

    @Test fun `secret is tolerant of case, spaces, dashes and padding`() {
        val loose = " gezd gnbv-gy3t qojq gezd gnbv gy3t qojq==== "
        assertEquals("287082", totpCode(loose, 59))
    }

    @Test fun `empty or invalid secrets are rejected without echoing them`() {
        assertFailsWith<IllegalArgumentException> { totpCode("", 59) }
        assertFailsWith<IllegalArgumentException> { totpCode("   ", 59) }
        val e = assertFailsWith<IllegalArgumentException> { totpCode("not-valid-base32!?!", 59) }
        assert(!e.message.orEmpty().contains("not-valid"))
    }
}
