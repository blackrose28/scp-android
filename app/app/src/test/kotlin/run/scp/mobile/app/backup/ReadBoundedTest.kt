package run.scp.mobile.app.backup

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ReadBoundedTest {
    @Test fun `reads everything up to the limit`() {
        val data = ByteArray(1000) { it.toByte() }
        assertContentEquals(data, readBounded(ByteArrayInputStream(data), 1000))
    }

    @Test fun `empty input is an empty array`() = assertContentEquals(ByteArray(0), readBounded(ByteArrayInputStream(ByteArray(0)), 10))

    @Test fun `one byte over the limit is rejected`() {
        assertFailsWith<BackupFormatException> { readBounded(ByteArrayInputStream(ByteArray(1001)), 1000) }
    }

    @Test fun `an endless stream is cut off, not read to the end`() {
        val endless = object : java.io.InputStream() { override fun read() = 7 }
        assertFailsWith<BackupFormatException> { readBounded(endless, 5_000) }
    }
}
