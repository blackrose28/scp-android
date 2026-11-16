package run.scp.mobile.app.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ESC = "\u001B"

private fun SpecialKey.enc(app: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
    String(encode(applicationCursor = app, ctrl = ctrl, alt = alt, shift = shift))

class SpecialKeyEncodingTest {
    @Test fun `arrows switch to SS3 in application cursor mode`() {
        assertEquals("$ESC[A", SpecialKey.Up.enc())
        assertEquals("${ESC}OA", SpecialKey.Up.enc(app = true))
        assertEquals("${ESC}OD", SpecialKey.Left.enc(app = true))
        assertEquals("$ESC[C", SpecialKey.Right.enc(app = false))
    }

    @Test fun `home and end follow the same rule`() {
        assertEquals("$ESC[H", SpecialKey.Home.enc())
        assertEquals("${ESC}OF", SpecialKey.End.enc(app = true))
    }

    @Test fun `modifiers use the xterm CSI 1 semicolon m form even in application mode`() {
        assertEquals("$ESC[1;5A", SpecialKey.Up.enc(ctrl = true))
        assertEquals("$ESC[1;3D", SpecialKey.Left.enc(alt = true, app = true))
        assertEquals("$ESC[1;2C", SpecialKey.Right.enc(shift = true))
        assertEquals("$ESC[1;7H", SpecialKey.Home.enc(ctrl = true, alt = true))
    }

    @Test fun `paging and editing keys are tilde sequences`() {
        assertEquals("$ESC[5~", SpecialKey.PageUp.enc())
        assertEquals("$ESC[6~", SpecialKey.PageDown.enc(app = true))
        assertEquals("$ESC[3~", SpecialKey.Delete.enc())
        assertEquals("$ESC[2~", SpecialKey.Insert.enc())
        assertEquals("$ESC[5;5~", SpecialKey.PageUp.enc(ctrl = true))
    }

    @Test fun `function keys`() {
        assertEquals("${ESC}OP", SpecialKey.F1.enc())
        assertEquals("${ESC}OS", SpecialKey.F4.enc(app = true))
        assertEquals("$ESC[15~", SpecialKey.F5.enc())
        assertEquals("$ESC[24~", SpecialKey.F12.enc())
        assertEquals("$ESC[1;2P", SpecialKey.F1.enc(shift = true))
        assertEquals("$ESC[17;5~", SpecialKey.F6.enc(ctrl = true))
    }

    @Test fun `alt prefixes a plain key with ESC`() {
        assertEquals("$ESC\u007F", SpecialKey.Backspace.enc(alt = true))
        assertEquals("$ESC\r", SpecialKey.Enter.enc(alt = true))
        assertEquals("\u007F", SpecialKey.Backspace.enc())
    }

    @Test fun `shift tab is back tab`() {
        assertEquals("$ESC[Z", SpecialKey.ShiftTab.enc())
    }

    @Test fun `bytes is the plain encoding`() {
        assertContentEquals(SpecialKey.Up.encode(), SpecialKey.Up.bytes)
    }
}

class ModifiersTest {
    private fun mod(text: String, ctrl: Boolean = false, alt: Boolean = false) = String(applyModifiers(text, ctrl, alt))

    @Test fun `no modifiers leaves text alone`() = assertEquals("héllo", mod("héllo"))

    @Test fun `ctrl turns a letter into its control code`() {
        assertEquals("\u0001", mod("a", ctrl = true))
        assertEquals("\u0003", mod("C", ctrl = true))
        assertEquals("\u001A", mod("z", ctrl = true))
    }

    @Test fun `ctrl on punctuation`() {
        assertEquals("\u0000", mod(" ", ctrl = true))
        assertEquals("\u001B", mod("[", ctrl = true))
        assertEquals("\u001C", mod("\\", ctrl = true))
        assertEquals("\u001D", mod("]", ctrl = true))
        assertEquals("\u001F", mod("_", ctrl = true))
        assertEquals("\u007F", mod("?", ctrl = true))
    }

    @Test fun `ctrl on something without a control code sends it unchanged`() = assertEquals("é", mod("é", ctrl = true))

    @Test fun `alt prefixes ESC`() {
        assertEquals("${ESC}x", mod("x", alt = true))
        assertEquals("$ESC\u0003", mod("c", ctrl = true, alt = true))
    }

    @Test fun `only the first character takes the modifiers`() = assertEquals("\u0001bc", mod("abc", ctrl = true))
}

class PasteTest {
    @Test fun `plain paste converts newlines to carriage returns`() {
        assertEquals("a\rb\rc", String(pasteBytes("a\nb\r\nc", bracketed = false)))
    }

    @Test fun `bracketed paste wraps and strips escapes`() {
        assertEquals("$ESC[200~echo hi\r$ESC[201~", String(pasteBytes("echo hi\n", bracketed = true)))
        assertEquals("$ESC[200~x[201~y$ESC[201~", String(pasteBytes("x${ESC}[201~y", bracketed = true)))
    }

    @Test fun `empty paste sends nothing`() {
        assertEquals(0, pasteBytes("", bracketed = true).size)
    }
}

class ScrollKeysTest {
    @Test fun `dragging down scrolls up so it sends Up keys`() {
        assertEquals(SpecialKey.Up to 3, scrollKeys(3))
    }

    @Test fun `dragging up sends Down keys`() {
        assertEquals(SpecialKey.Down to 2, scrollKeys(-2))
    }

    @Test fun `zero lines sends nothing`() = assertNull(scrollKeys(0))

    @Test fun `a fling is capped`() {
        assertEquals(SpecialKey.Up to 10, scrollKeys(500))
    }
}

class HardwareKeyTest {
    @Test fun `navigation keys map to special keys`() {
        assertEquals(SpecialKey.Up, hardwareKey(Key.DirectionUp, shift = false))
        assertEquals(SpecialKey.PageDown, hardwareKey(Key.PageDown, shift = false))
        assertEquals(SpecialKey.Esc, hardwareKey(Key.Escape, shift = false))
        assertEquals(SpecialKey.Delete, hardwareKey(Key.Delete, shift = false))
        assertEquals(SpecialKey.F5, hardwareKey(Key.F5, shift = false))
        assertEquals(SpecialKey.Backspace, hardwareKey(Key.Backspace, shift = false))
        assertEquals(SpecialKey.Enter, hardwareKey(Key.Enter, shift = false))
    }

    @Test fun `tab with shift is back tab`() {
        assertEquals(SpecialKey.Tab, hardwareKey(Key.Tab, shift = false))
        assertEquals(SpecialKey.ShiftTab, hardwareKey(Key.Tab, shift = true))
    }

    @Test fun `letters are not special keys`() = assertNull(hardwareKey(Key.A, shift = false))
}
