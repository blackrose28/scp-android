package run.scp.mobile.app.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ESC = "\u001B"

class TerminalEmulatorTest {
    private class Rig(cols: Int = 10, rows: Int = 4, scrollback: Int = 100) {
        val replies = ArrayList<String>()
        val emu = TerminalEmulator(cols, rows, scrollback) { replies.add(String(it, Charsets.UTF_8)) }
        fun feed(s: String) = emu.append(s.toByteArray(Charsets.UTF_8))
        fun screen() = emu.snapshot().screenLines()
        fun snap() = emu.snapshot()
    }

    private fun rig(cols: Int = 10, rows: Int = 4, scrollback: Int = 100) = Rig(cols, rows, scrollback)

    // --- printing, controls, wrapping ---

    @Test fun `text and CRLF fill rows`() {
        val r = rig()
        r.feed("one\r\ntwo")
        assertEquals(listOf("one", "two", "", ""), r.screen())
    }

    @Test fun `bare LF keeps the column`() {
        val r = rig()
        r.feed("ab\ncd")
        assertEquals(listOf("ab", "  cd", "", ""), r.screen())
    }

    @Test fun `CR overwrites and BS moves left without erasing`() {
        val r = rig()
        r.feed("hello\rHE")
        r.feed("\b\bX")
        assertEquals("XEllo", r.screen()[0])
    }

    @Test fun `tab advances to the next stop of eight`() {
        val r = rig(cols = 20)
        r.feed("a\tb")
        assertEquals("a       b", r.screen()[0])
    }

    @Test fun `text wraps at the right edge`() {
        val r = rig(cols = 5)
        r.feed("abcdefg")
        assertEquals(listOf("abcde", "fg", "", ""), r.screen())
    }

    @Test fun `wrap is deferred so a full line followed by CRLF leaves no blank row`() {
        val r = rig(cols = 5)
        r.feed("abcde\r\nxy")
        assertEquals(listOf("abcde", "xy", "", ""), r.screen())
    }

    @Test fun `with autowrap off the last column is overwritten`() {
        val r = rig(cols = 5)
        r.feed("$ESC[?7labcdefg")
        assertEquals("abcdg", r.screen()[0])
        assertEquals("", r.screen()[1])
    }

    @Test fun `output past the bottom scrolls into scrollback`() {
        val r = rig(rows = 3)
        r.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(listOf("3", "4", "5"), r.screen())
        assertEquals(listOf("1", "2"), r.snap().scrollbackLines())
    }

    @Test fun `scrollback is capped dropping the oldest`() {
        val r = rig(rows = 2, scrollback = 3)
        r.feed((1..10).joinToString("\r\n"))
        assertEquals(listOf("6", "7", "8"), r.snap().scrollbackLines())
        assertEquals(listOf("9", "10"), r.screen())
    }

    @Test fun `bell and unknown C0 controls are ignored`() {
        val r = rig()
        r.feed("a\u0007\u0001b")
        assertEquals("ab", r.screen()[0])
    }

    // --- cursor movement ---

    @Test fun `CUP positions the cursor 1-based`() {
        val r = rig()
        r.feed("$ESC[2;3HX")
        assertEquals(listOf("", "  X", "", ""), r.screen())
        assertEquals(1, r.snap().cursorRow)
        assertEquals(3, r.snap().cursorCol)
    }

    @Test fun `CUP with no parameters goes home and out of range clamps`() {
        val r = rig()
        r.feed("xxx$ESC[H" + "Y")
        assertEquals("Yxx", r.screen()[0])
        r.feed("$ESC[99;99HZ")
        assertEquals("         Z", r.screen()[3])
    }

    @Test fun `relative cursor moves clamp at the edges`() {
        val r = rig()
        r.feed("$ESC[3B$ESC[5CX") // down 3, right 5
        assertEquals("     X", r.screen()[3])
        r.feed("$ESC[10A$ESC[20DY") // up 10 (clamp), left 20 (clamp)
        assertEquals("Y", r.screen()[0])
    }

    @Test fun `CHA and VPA are absolute`() {
        val r = rig()
        r.feed("$ESC[4G" + "a$ESC[3d" + "b")
        assertEquals("   a", r.screen()[0])
        assertEquals("    b", r.screen()[2])
    }

    @Test fun `CNL and CPL move lines and reset the column`() {
        val r = rig()
        r.feed("abc$ESC[2EX")
        assertEquals("X", r.screen()[2])
        r.feed("$ESC[1FY")
        assertEquals("Y", r.screen()[1])
    }

    @Test fun `save and restore cursor with ESC 7 8 and CSI s u`() {
        val r = rig()
        r.feed("ab${ESC}7\r\ncd${ESC}8X")
        assertEquals("abX", r.screen()[0])
        r.feed("$ESC[s\r\n\r\n$ESC[uY")
        assertEquals("abXY", r.screen()[0])
    }

    @Test fun `IND NEL and RI`() {
        val r = rig()
        r.feed("ab${ESC}Dcd")
        assertEquals("  cd", r.screen()[1])
        r.feed("${ESC}Eef")
        assertEquals("ef", r.screen()[2])
    }

    @Test fun `RI at the top of the screen scrolls down`() {
        val r = rig()
        r.feed("a\r\nb$ESC[H${ESC}M")
        assertEquals(listOf("", "a", "b", ""), r.screen())
        r.feed("Y")
        assertEquals("Y", r.screen()[0]) // the cursor stays on row 0
    }

    @Test fun `tab stops can be set and cleared`() {
        val r = rig(cols = 20)
        r.feed("$ESC[3g") // clear all
        r.feed("$ESC[1;5H${ESC}H") // set a stop at column 4
        r.feed("\r\tX")
        assertEquals("    X", r.screen()[0])
        r.feed("\r$ESC[3I") // CHT with no further stops stops at the last column
        assertEquals(19, r.snap().cursorCol)
    }

    @Test fun `CBT moves back to the previous tab stop`() {
        val r = rig(cols = 20)
        r.feed("$ESC[1;12H$ESC[ZX")
        assertEquals("        X", r.screen()[0])
    }

    // --- erasing and editing ---

    @Test fun `EL erases to the end, to the start, or the whole line`() {
        val r = rig()
        r.feed("abcdef$ESC[1;4H$ESC[K")
        assertEquals("abc", r.screen()[0])
        r.feed("$ESC[1;2H$ESC[1K")
        assertEquals("  c", r.screen()[0])
        r.feed("$ESC[2K")
        assertEquals("", r.screen()[0])
    }

    @Test fun `ED erases below, above, or everything`() {
        val r = rig()
        r.feed("aa\r\nbb\r\ncc\r\ndd$ESC[2;2H$ESC[0J")
        assertEquals(listOf("aa", "b", "", ""), r.screen())
        r.feed("$ESC[3;1H$ESC[1J")
        assertEquals(listOf("", "", " ".repeat(0), ""), r.screen().map { it.trimEnd() })
        r.feed("xyz$ESC[2J")
        assertEquals(listOf("", "", "", ""), r.screen())
    }

    @Test fun `ED 3 clears scrollback`() {
        val r = rig(rows = 2)
        r.feed("1\r\n2\r\n3")
        assertEquals(listOf("1"), r.snap().scrollbackLines())
        r.feed("$ESC[3J")
        assertEquals(emptyList(), r.snap().scrollbackLines())
        assertEquals(listOf("2", "3"), r.screen())
    }

    @Test fun `ECH blanks cells without moving the cursor`() {
        val r = rig()
        r.feed("abcdef$ESC[1;2H$ESC[3XZ")
        assertEquals("aZ  ef", r.screen()[0])
    }

    @Test fun `ICH and DCH shift the rest of the line`() {
        val r = rig()
        r.feed("abcdef$ESC[1;3H$ESC[2@")
        assertEquals("ab  cdef", r.screen()[0])
        r.feed("$ESC[3P")
        assertEquals("abdef", r.screen()[0])
    }

    @Test fun `IL and DL insert and delete rows`() {
        val r = rig()
        r.feed("a\r\nb\r\nc\r\nd$ESC[2;1H$ESC[L")
        assertEquals(listOf("a", "", "b", "c"), r.screen())
        r.feed("$ESC[2M")
        assertEquals(listOf("a", "c", "", ""), r.screen().map { it.trimEnd() })
    }

    @Test fun `insert mode shifts existing text right`() {
        val r = rig()
        r.feed("abc$ESC[1G$ESC[4hX$ESC[4lY")
        assertEquals("XYbc", r.screen()[0])
    }

    @Test fun `REP repeats the previous character`() {
        val r = rig()
        r.feed("a$ESC[3b")
        assertEquals("aaaa", r.screen()[0])
    }

    // --- scroll regions ---

    @Test fun `scroll region confines scrolling and LF at its bottom does not touch other rows`() {
        val r = rig(rows = 4)
        r.feed("top\r\n1\r\n2\r\nbot")
        r.feed("$ESC[2;3r") // rows 2..3; cursor homes
        r.feed("$ESC[3;1H\n") // LF at region bottom
        assertEquals(listOf("top", "2", "", "bot"), r.screen().map { it.trimEnd() })
        assertEquals(emptyList(), r.snap().scrollbackLines()) // partial region never feeds scrollback
    }

    @Test fun `SU and SD scroll within the region`() {
        val r = rig(rows = 3)
        r.feed("a\r\nb\r\nc$ESC[1S")
        assertEquals(listOf("b", "c", ""), r.screen())
        r.feed("$ESC[1T")
        assertEquals(listOf("", "b", "c"), r.screen())
    }

    @Test fun `RI at the top of a region scrolls only the region down`() {
        val r = rig(rows = 4)
        r.feed("t\r\na\r\nb\r\nz")
        r.feed("$ESC[2;3r$ESC[2;1H${ESC}M")
        assertEquals(listOf("t", "", "a", "z"), r.screen())
    }

    // --- alternate screen ---

    @Test fun `alternate screen is separate, restores main, and never feeds scrollback`() {
        val r = rig(rows = 3)
        r.feed("main\r\nline2")
        r.feed("$ESC[?1049h")
        assertTrue(r.snap().alternateScreen)
        assertEquals(listOf("", "", ""), r.screen())
        r.feed("alt1\r\nalt2\r\nalt3\r\nalt4\r\nalt5")
        assertEquals(listOf("alt3", "alt4", "alt5"), r.screen())
        assertEquals(emptyList(), r.snap().scrollbackLines())
        r.feed("$ESC[?1049l")
        assertFalse(r.snap().alternateScreen)
        assertEquals(listOf("main", "line2", ""), r.screen())
        assertEquals(1, r.snap().cursorRow) // cursor restored to where it was on main, not where alt left it
        assertEquals(5, r.snap().cursorCol)
    }

    @Test fun `entering the alternate screen again starts blank`() {
        val r = rig(rows = 2)
        r.feed("$ESC[?1049hxx$ESC[?1049l$ESC[?1049h")
        assertEquals(listOf("", ""), r.screen())
    }

    @Test fun `47 and 1047 also switch screens`() {
        val r = rig(rows = 2)
        r.feed("m$ESC[?47hA$ESC[?47l")
        assertEquals("m", r.screen()[0])
        r.feed("$ESC[?1047hB$ESC[?1047l")
        assertEquals("m", r.screen()[0])
    }

    // --- SGR ---

    private fun Rig.style(row: Int, col: Int) = snap().lines[snap().screenTop + row].styles[col]

    @Test fun `SGR basic attributes and 16 colours`() {
        val r = rig()
        r.feed("$ESC[1;4;31;44mA$ESC[0mB")
        val a = r.style(0, 0)
        assertEquals(Style.BOLD or Style.UNDERLINE, Style.attrs(a))
        assertEquals(TermColor.indexed(1), Style.fg(a))
        assertEquals(TermColor.indexed(4), Style.bg(a))
        assertEquals(Style.DEFAULT, r.style(0, 1))
    }

    @Test fun `SGR bright colours 90 and 100 map to indices 8 to 15`() {
        val r = rig()
        r.feed("$ESC[91;102mA")
        assertEquals(TermColor.indexed(9), Style.fg(r.style(0, 0)))
        assertEquals(TermColor.indexed(10), Style.bg(r.style(0, 0)))
    }

    @Test fun `SGR 256 colour and truecolour in both separators`() {
        val r = rig()
        r.feed("$ESC[38;5;196;48;5;21mA$ESC[38;2;1;2;3mB$ESC[38:2::10:20:30mC")
        assertEquals(TermColor.indexed(196), Style.fg(r.style(0, 0)))
        assertEquals(TermColor.indexed(21), Style.bg(r.style(0, 0)))
        assertEquals(TermColor.rgb(1, 2, 3), Style.fg(r.style(0, 1)))
        assertEquals(TermColor.rgb(10, 20, 30), Style.fg(r.style(0, 2)))
    }

    @Test fun `SGR resets individual attributes and default colours`() {
        val r = rig()
        r.feed("$ESC[1;3;7;9;31;42m$ESC[22;23;27;29;39;49mA")
        assertEquals(Style.DEFAULT, r.style(0, 0))
        r.feed("$ESC[2;5;8mB")
        assertEquals(Style.DIM or Style.BLINK or Style.INVISIBLE, Style.attrs(r.style(0, 1)))
    }

    @Test fun `SGR with no parameter resets`() {
        val r = rig()
        r.feed("$ESC[1m$ESC[mA")
        assertEquals(Style.DEFAULT, r.style(0, 0))
    }

    @Test fun `erasing keeps the current background so full-screen apps can paint`() {
        val r = rig()
        r.feed("$ESC[44m$ESC[2J$ESC[H")
        assertEquals(TermColor.indexed(4), Style.bg(r.style(2, 5)))
        r.feed("$ESC[0m$ESC[2K")
        assertEquals(Style.DEFAULT, r.style(0, 0))
    }

    @Test fun `scrolled-in rows take the current background too`() {
        val r = rig(rows = 2)
        r.feed("$ESC[41m\r\n\r\n")
        assertEquals(TermColor.indexed(1), Style.bg(r.style(1, 0)))
    }

    // --- charsets ---

    @Test fun `DEC special graphics draws boxes and ESC B returns to ASCII`() {
        val r = rig()
        r.feed("$ESC(0lqqk$ESC(Bq")
        assertEquals("┌──┐q", r.screen()[0])
    }

    @Test fun `SO and SI switch between G0 and G1`() {
        val r = rig()
        r.feed("$ESC)0\u000Ex\u000Fx")
        assertEquals("│x", r.screen()[0])
    }

    // --- width ---

    @Test fun `wide characters take two cells`() {
        val r = rig()
        r.feed("漢a")
        val line = r.snap().lines[r.snap().screenTop]
        assertEquals('漢'.code, line.cps[0])
        assertEquals(TerminalLine.SPACER, line.cps[1])
        assertEquals('a'.code, line.cps[2])
        assertEquals("漢a", r.screen()[0])
        assertEquals(3, r.snap().cursorCol)
    }

    @Test fun `a wide character that does not fit wraps whole`() {
        val r = rig(cols = 5)
        r.feed("abcd漢")
        assertEquals(listOf("abcd", "漢", "", ""), r.screen())
    }

    @Test fun `emoji outside the BMP is one wide cell`() {
        val r = rig()
        r.feed("😀x")
        assertEquals("😀x", r.screen()[0])
        assertEquals(3, r.snap().cursorCol)
    }

    @Test fun `overwriting half of a wide character blanks the other half`() {
        val r = rig()
        r.feed("漢$ESC[1GX")
        assertEquals("X", r.screen()[0].trimEnd())
        r.feed("$ESC[1G漢$ESC[2GY")
        assertEquals(" Y", r.screen()[0])
    }

    @Test fun `combining marks compose with the previous character`() {
        val r = rig()
        r.feed("éx")
        assertEquals("éx", r.screen()[0])
        assertEquals(2, r.snap().cursorCol)
    }

    @Test fun `zero width joiner and variation selectors take no cell`() {
        val r = rig()
        r.feed("a‍b️c")
        assertEquals("abc", r.screen()[0])
    }

    // --- modes and replies ---

    @Test fun `private modes are tracked`() {
        val r = rig()
        assertFalse(r.snap().applicationCursorKeys)
        assertTrue(r.snap().cursorVisible)
        assertFalse(r.snap().bracketedPaste)
        r.feed("$ESC[?1h$ESC[?25l$ESC[?2004h")
        assertTrue(r.snap().applicationCursorKeys)
        assertFalse(r.snap().cursorVisible)
        assertTrue(r.snap().bracketedPaste)
        r.feed("$ESC[?1l$ESC[?25h$ESC[?2004l")
        assertFalse(r.snap().applicationCursorKeys)
        assertTrue(r.snap().cursorVisible)
        assertFalse(r.snap().bracketedPaste)
    }

    @Test fun `several private modes in one sequence`() {
        val r = rig()
        r.feed("$ESC[?1;25;2004l")
        assertFalse(r.snap().cursorVisible)
        r.feed("$ESC[?1;25;2004h")
        assertTrue(r.snap().applicationCursorKeys && r.snap().cursorVisible && r.snap().bracketedPaste)
    }

    @Test fun `DSR reports status and the cursor position`() {
        val r = rig()
        r.feed("$ESC[5n")
        r.feed("$ESC[2;3H$ESC[6n")
        assertEquals(listOf("$ESC[0n", "$ESC[2;3R"), r.replies)
    }

    @Test fun `device attributes get an answer`() {
        val r = rig()
        r.feed("$ESC[c$ESC[>c")
        assertEquals(2, r.replies.size)
        assertTrue(r.replies[0].startsWith("$ESC[?") && r.replies[0].endsWith("c"))
        assertTrue(r.replies[1].startsWith("$ESC[>") && r.replies[1].endsWith("c"))
    }

    // --- parser robustness ---

    @Test fun `OSC and DCS strings are swallowed up to BEL or ST`() {
        val r = rig()
        r.feed("a${ESC}]0;title\u0007b${ESC}]2;t2$ESC\\c${ESC}Pq#0;2;0;0;0$ESC\\d")
        assertEquals("abcd", r.screen()[0])
    }

    @Test fun `unknown sequences are dropped without eating text`() {
        val r = rig(cols = 40)
        r.feed("$ESC[>4;2m$ESC[?1004h$ESC[22;0;0t$ESC[ q$ESC=$ESC>${ESC}#8ok")
        assertEquals("ok", r.screen()[0])
    }

    @Test fun `escape sequences and UTF-8 split across chunks`() {
        val r = rig()
        val bytes = "$ESC[31mé漢".toByteArray()
        for (b in bytes) r.emu.append(byteArrayOf(b))
        assertEquals("é漢", r.screen()[0])
        assertEquals(TermColor.indexed(1), Style.fg(r.style(0, 0)))
    }

    @Test fun `CAN cancels a sequence in progress`() {
        val r = rig()
        r.feed("$ESC[3\u0018x")
        assertEquals("x", r.screen()[0])
    }

    @Test fun `invalid UTF-8 becomes a replacement character`() {
        val r = rig()
        r.emu.append(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        assertEquals("a�b", r.screen()[0])
    }

    @Test fun `full reset clears the screen and modes`() {
        val r = rig()
        r.feed("abc$ESC[?1h$ESC[31m${ESC}c")
        assertEquals(listOf("", "", "", ""), r.screen())
        assertFalse(r.snap().applicationCursorKeys)
        r.feed("x")
        assertEquals(Style.DEFAULT, r.style(0, 0))
    }

    @Test fun `soft reset restores modes but keeps the screen`() {
        val r = rig()
        r.feed("abc$ESC[?1h$ESC[!p")
        assertEquals("abc", r.screen()[0])
        assertFalse(r.snap().applicationCursorKeys)
    }

    // --- resize ---

    @Test fun `shrinking rows pushes the top into scrollback to keep the cursor visible`() {
        val r = rig(rows = 4)
        r.feed("1\r\n2\r\n3\r\n4")
        r.emu.resize(10, 2)
        assertEquals(listOf("3", "4"), r.screen())
        assertEquals(listOf("1", "2"), r.snap().scrollbackLines())
        assertEquals(1, r.snap().cursorRow)
    }

    @Test fun `growing rows brings scrollback back`() {
        val r = rig(rows = 4)
        r.feed("1\r\n2\r\n3\r\n4")
        r.emu.resize(10, 2)
        r.emu.resize(10, 4)
        assertEquals(listOf("1", "2", "3", "4"), r.screen())
        assertEquals(emptyList(), r.snap().scrollbackLines())
        assertEquals(3, r.snap().cursorRow)
    }

    @Test fun `growing with no scrollback adds blank rows below`() {
        val r = rig(rows = 2)
        r.feed("a\r\nb")
        r.emu.resize(10, 4)
        assertEquals(listOf("a", "b", "", ""), r.screen())
        assertEquals(1, r.snap().cursorRow)
    }

    @Test fun `shrinking rows with the cursor at the top drops rows from the bottom`() {
        val r = rig(rows = 4)
        r.feed("1\r\n2\r\n3\r\n4$ESC[H")
        r.emu.resize(10, 2)
        assertEquals(listOf("1", "2"), r.screen())
        assertEquals(emptyList(), r.snap().scrollbackLines())
    }

    @Test fun `column changes truncate and pad rows and clamp the cursor`() {
        val r = rig(cols = 10)
        r.feed("0123456789")
        r.emu.resize(5, 4)
        assertEquals("01234", r.screen()[0])
        assertEquals(4, r.snap().cursorCol)
        r.emu.resize(12, 4)
        assertEquals(12, r.snap().lines[r.snap().screenTop].cps.size)
        assertEquals(12, r.snap().cols)
    }

    @Test fun `resize resets the scroll region`() {
        val r = rig(rows = 4)
        r.feed("$ESC[2;3r")
        r.emu.resize(10, 5)
        r.feed("$ESC[5;1H1\r\n2")
        assertEquals("1", r.screen()[3])
        assertEquals("2", r.screen()[4])
    }

    @Test fun `resizing while on the alternate screen loses no main lines and moves the saved cursor with them`() {
        val r = rig(rows = 3)
        r.feed("m1\r\nm2\r\nm3\r\nm4")
        r.feed("$ESC[?1049hx")
        r.emu.resize(10, 2)
        r.emu.resize(10, 5)
        assertEquals(5, r.screen().size)
        assertEquals(emptyList(), r.snap().scrollbackLines()) // the alternate screen has no scrollback
        r.feed("$ESC[?1049l")
        assertEquals(listOf("m1", "m2", "m3", "m4", ""), r.screen())
        assertEquals(3, r.snap().cursorRow) // still on m4's row
    }

    // --- snapshots ---

    @Test fun `a snapshot is not changed by later output`() {
        val r = rig()
        r.feed("abc")
        val s = r.snap()
        r.feed("\r\nxyz$ESC[1;1HQ")
        assertEquals("abc", s.screenLines()[0])
        assertEquals("", s.screenLines()[1])
    }

    @Test fun `text drops trailing blank rows`() {
        val r = rig()
        assertEquals(emptyList(), r.snap().text())
        r.feed("a\r\n\r\nb")
        assertEquals(listOf("a", "", "b"), r.snap().text())
    }

    @Test fun `visible window is scrollback followed by the screen`() {
        val r = rig(rows = 2)
        r.feed("1\r\n2\r\n3")
        val s = r.snap()
        assertEquals(3, s.lines.size)
        assertEquals(1, s.screenTop)
        assertContentEquals(intArrayOf('1'.code), s.lines[0].cps.copyOf(1))
    }
}
