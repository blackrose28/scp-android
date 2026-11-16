package run.scp.mobile.app.secure

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.scp.mobile.app.session.BoundaryToken

class SealedFileBoundaryTokenStoreTest {
    private val file = File(createTempDirectory("tokens").toFile(), "boundary_tokens.enc")
    private fun store(sealer: Sealer = AesSealer()) = SealedFileBoundaryTokenStore(file, sealer)

    @Test fun `missing file loads as no token`() {
        assertNull(store().load("k"))
    }

    @Test fun `token round trips across instances`() {
        store().save("k", BoundaryToken("at_secret", 1_700_000_000_000))
        assertEquals(BoundaryToken("at_secret", 1_700_000_000_000), store().load("k"))
    }

    @Test fun `tokens are kept per key`() {
        val s = store()
        s.save("a", BoundaryToken("ta", 1))
        s.save("b", BoundaryToken("tb", 2))
        assertEquals("ta", s.load("a")?.value)
        assertEquals("tb", s.load("b")?.value)
        assertNull(s.load("c"))
    }

    @Test fun `saving a key replaces its token`() {
        val s = store()
        s.save("a", BoundaryToken("old", 1))
        s.save("a", BoundaryToken("new", 2))
        assertEquals(BoundaryToken("new", 2), store().load("a"))
    }

    @Test fun `remove forgets only that key`() {
        val s = store()
        s.save("a", BoundaryToken("ta", 1))
        s.save("b", BoundaryToken("tb", 2))
        s.remove("a")
        assertNull(store().load("a"))
        assertEquals("tb", store().load("b")?.value)
    }

    @Test fun `removing the last token deletes the file`() {
        val s = store()
        s.save("a", BoundaryToken("ta", 1))
        s.remove("a")
        assertFalse(file.exists())
    }

    @Test fun `removing an unknown key is a no-op`() {
        store().remove("nope")
        assertFalse(file.exists())
    }

    @Test fun `the file never contains plaintext`() {
        store().save("https://boundary.example\u0000amoidc_abc", BoundaryToken("at_hunter2-token", 5))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("hunter2-token") || text.contains("amoidc_abc") || text.contains("boundary.example"))
    }

    @Test fun `a file sealed with a different key loads as no token instead of crashing`() {
        store().save("k", BoundaryToken("t", 1))
        assertNull(store(AesSealer(ByteArray(32) { 9 })).load("k"))
    }

    @Test fun `a corrupt file loads as no token and can be overwritten`() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        val s = store()
        assertNull(s.load("k"))
        s.save("k", BoundaryToken("t", 1))
        assertEquals("t", store().load("k")?.value)
    }

    @Test fun `no temp files are left behind`() {
        store().save("k", BoundaryToken("t", 1))
        assertEquals(listOf(file.name), file.parentFile.list()!!.toList())
    }

    @Test fun `a failing seal propagates and leaves the previous file intact`() {
        val s = store()
        s.save("k", BoundaryToken("t", 1))
        val broken = object : Sealer {
            override fun seal(plain: ByteArray): ByteArray = error("keystore unavailable")
            override fun open(blob: ByteArray): ByteArray = AesSealer().open(blob)
        }
        val failed = runCatching { store(broken).save("k2", BoundaryToken("x", 2)) }
        assertTrue(failed.isFailure)
        assertEquals("t", store().load("k")?.value)
    }
}
