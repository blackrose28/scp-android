package run.scp.mobile.app.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteTest {
    private val defaultFg = 0xFFE0E0E0.toInt()

    @Test fun `default colour resolves to the supplied default`() {
        assertEquals(defaultFg, Palette.argb(TermColor.DEFAULT, defaultFg))
    }

    @Test fun `rgb colours are opaque`() {
        assertEquals(0xFF0A141E.toInt(), Palette.argb(TermColor.rgb(10, 20, 30), defaultFg))
    }

    @Test fun `the 16 ANSI colours`() {
        assertEquals(0xFF000000.toInt(), Palette.argb(TermColor.indexed(0), defaultFg))
        assertEquals(0xFFCD3131.toInt(), Palette.argb(TermColor.indexed(1), defaultFg))
        assertEquals(0xFFE5E5E5.toInt(), Palette.argb(TermColor.indexed(7), defaultFg))
        assertEquals(0xFFFFFFFF.toInt(), Palette.argb(TermColor.indexed(15), defaultFg))
    }

    @Test fun `the 6x6x6 colour cube`() {
        assertEquals(0xFF000000.toInt(), Palette.argb(TermColor.indexed(16), defaultFg))
        assertEquals(0xFF0000FF.toInt(), Palette.argb(TermColor.indexed(21), defaultFg))
        assertEquals(0xFF5F87AF.toInt(), Palette.argb(TermColor.indexed(16 + 36 * 1 + 6 * 2 + 3), defaultFg))
        assertEquals(0xFFFFFFFF.toInt(), Palette.argb(TermColor.indexed(231), defaultFg))
    }

    @Test fun `the grayscale ramp`() {
        assertEquals(0xFF080808.toInt(), Palette.argb(TermColor.indexed(232), defaultFg))
        assertEquals(0xFFEEEEEE.toInt(), Palette.argb(TermColor.indexed(255), defaultFg))
    }

    @Test fun `bold brightens the dark eight colours only`() {
        assertEquals(Palette.argb(TermColor.indexed(9), defaultFg), Palette.argb(TermColor.indexed(1), defaultFg, bold = true))
        assertEquals(Palette.argb(TermColor.indexed(9), defaultFg), Palette.argb(TermColor.indexed(9), defaultFg, bold = true))
        assertEquals(Palette.argb(TermColor.indexed(100), defaultFg), Palette.argb(TermColor.indexed(100), defaultFg, bold = true))
        assertEquals(defaultFg, Palette.argb(TermColor.DEFAULT, defaultFg, bold = true))
    }
}
