package run.scp.mobile.app.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpecialKeyTest {
    @Test fun `control keys map to their bytes`() {
        assertContentEquals(byteArrayOf(0x1B), SpecialKey.Esc.bytes)
        assertContentEquals(byteArrayOf(0x09), SpecialKey.Tab.bytes)
        assertContentEquals(byteArrayOf(0x03), SpecialKey.CtrlC.bytes)
        assertContentEquals(byteArrayOf(0x04), SpecialKey.CtrlD.bytes)
        assertContentEquals(byteArrayOf(0x7F), SpecialKey.Backspace.bytes)
        assertContentEquals(byteArrayOf(0x0D), SpecialKey.Enter.bytes)
    }

    @Test fun `arrows use normal-mode CSI sequences`() {
        assertEquals("\u001B[A", String(SpecialKey.Up.bytes))
        assertEquals("\u001B[B", String(SpecialKey.Down.bytes))
        assertEquals("\u001B[C", String(SpecialKey.Right.bytes))
        assertEquals("\u001B[D", String(SpecialKey.Left.bytes))
    }
}

class TypedBytesTest {
    @Test fun `every typed chunk is forwarded as utf-8, repeats included`() {
        assertContentEquals("o".toByteArray(), typedBytes("o", connected = true))
        assertContentEquals("o".toByteArray(), typedBytes("o", connected = true))
        assertContentEquals("é€".toByteArray(Charsets.UTF_8), typedBytes("é€", connected = true))
    }

    @Test fun `nothing is sent when empty or not connected`() {
        kotlin.test.assertNull(typedBytes("", connected = true))
        kotlin.test.assertNull(typedBytes("x", connected = false))
    }
}
