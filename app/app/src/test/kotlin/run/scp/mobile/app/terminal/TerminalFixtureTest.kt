package run.scp.mobile.app.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Replays bytes captured from real full-screen programs (see resources/terminal/README.md). */
class TerminalFixtureTest {
    private fun replay(name: String, chunk: Int = Int.MAX_VALUE): TerminalEmulator {
        val bytes = javaClass.getResourceAsStream("/terminal/$name.bin")!!.readBytes()
        val emu = TerminalEmulator(80, 24)
        bytes.toList().chunked(minOf(chunk, bytes.size)).forEach { emu.append(it.toByteArray()) }
        return emu
    }

    private fun ScreenSnapshot.style(row: Int, col: Int) = lines[screenTop + row].styles[col]

    @Test fun `less shows the second page on the alternate screen with its prompt`() {
        val s = replay("less").snapshot()
        assertTrue(s.alternateScreen)
        assertEquals("line 24: the quick brown fox", s.screenLines()[0])
        assertEquals("line 46: the quick brown fox", s.screenLines()[22])
        assertEquals(":", s.screenLines()[23])
        assertEquals(23, s.cursorRow)
        assertEquals(1, s.cursorCol)
        assertTrue(s.applicationCursorKeys) // less sends ESC[?1h
        assertEquals(emptyList(), s.scrollbackLines())
    }

    @Test fun `nano draws its inverse title bar and shortcut rows`() {
        val s = replay("nano").snapshot()
        assertTrue(s.screenLines()[0].contains("GNU nano"))
        assertTrue(s.screenLines()[0].endsWith("sample.txt"))
        assertEquals("line 1: the quick brown fox", s.screenLines()[1])
        assertTrue(s.screenLines()[21].contains("[ Read 79 lines ]"))
        assertTrue(s.screenLines()[22].startsWith("^G Help"))
        assertTrue(s.screenLines()[23].startsWith("^X Exit"))
        assertNotEquals(0, Style.attrs(s.style(0, 5)) and Style.INVERSE, "title bar is reverse video")
        assertNotEquals(0, Style.attrs(s.style(23, 0)) and Style.INVERSE, "^X key label is reverse video")
        assertEquals(0, Style.attrs(s.style(1, 0)) and Style.INVERSE, "body text is plain")
    }

    @Test fun `htop lays out meters, process table and function key bar`() {
        val s = replay("htop").snapshot()
        val lines = s.screenLines()
        assertTrue(lines[1].contains("1[") && lines[1].contains("4["), "CPU meters")
        assertTrue(lines.any { it.contains("Mem[") })
        val header = lines.indexOfFirst { it.contains("PID USER") }
        assertTrue(header in 6..10, "process table header at row $header")
        assertTrue(lines[23].startsWith("F1Help") && lines[23].contains("F10Quit"))
        assertNotEquals(TermColor.DEFAULT, Style.bg(s.style(header, 2)), "the table header has a background colour")
        assertNotEquals(TermColor.DEFAULT, Style.bg(s.style(23, 3)), "the 'Help' label of the F-key bar is coloured")
    }

    @Test fun `vi shows the end of the file after G`() {
        val s = replay("vi").snapshot()
        assertEquals("line 57: the quick brown fox", s.screenLines()[0])
        assertEquals("line 79: the quick brown fox", s.screenLines()[22])
        assertEquals(22, s.cursorRow)
    }

    @Test fun `the same screens result when the bytes arrive one at a time`() {
        for (name in listOf("less", "nano", "htop", "vi")) {
            val whole = replay(name).snapshot()
            val trickled = replay(name, chunk = 1).snapshot()
            assertEquals(whole.screenLines(), trickled.screenLines(), name)
            assertEquals(whole.cursorRow to whole.cursorCol, trickled.cursorRow to trickled.cursorCol, name)
        }
    }

    @Test fun `resizing a full-screen program keeps it consistent`() {
        val emu = replay("nano")
        emu.resize(60, 12)
        emu.resize(80, 24)
        val s = emu.snapshot()
        assertEquals(24, s.screenLines().size)
        assertTrue(s.alternateScreen)
    }
}
