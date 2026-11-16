package run.scp.mobile.app.terminal

/**
 * One row of cells. [cps] holds the code point of each cell: 0 for an empty cell, [SPACER] for the
 * right half of a wide character, anything else is the character. [styles] is parallel, see [Style].
 * Mutable while it is on the live screen; a line that has scrolled into scrollback, or that is part
 * of a [ScreenSnapshot], is never mutated again.
 */
class TerminalLine(val cps: IntArray, val styles: LongArray) {
    constructor(cols: Int, fill: Long = Style.DEFAULT) : this(IntArray(cols), LongArray(cols) { fill })

    val size: Int get() = cps.size

    fun copy() = TerminalLine(cps.copyOf(), styles.copyOf())

    /** This line made [cols] wide, padding with [fill]; a wide character cut in half is blanked. */
    fun resized(cols: Int, fill: Long = Style.DEFAULT): TerminalLine {
        if (cols == cps.size) return this
        val out = TerminalLine(cols, fill)
        val n = minOf(cols, cps.size)
        System.arraycopy(cps, 0, out.cps, 0, n)
        System.arraycopy(styles, 0, out.styles, 0, n)
        if (n in 1 until cps.size && cps[n] == SPACER) out.cps[n - 1] = 0
        return out
    }

    /** The visible text, empty cells as spaces, wide-character halves collapsed, trailing blanks trimmed. */
    fun text(): String {
        val sb = StringBuilder(cps.size)
        for (cp in cps) {
            when {
                cp == SPACER -> Unit
                cp == 0 -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }

    companion object {
        const val SPACER = -1
    }
}

/**
 * An immutable picture of the terminal: [lines] is the scrollback followed by the live screen,
 * [screenTop] is the index of the screen's first row. The alternate screen has no scrollback, so
 * there [screenTop] is 0.
 */
class ScreenSnapshot(
    private val scrollback: List<TerminalLine>,
    private val screen: List<TerminalLine>,
    val cols: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val applicationCursorKeys: Boolean,
    val bracketedPaste: Boolean,
    val alternateScreen: Boolean,
) {
    val screenTop: Int get() = scrollback.size

    /** Scrollback then screen, without copying either. */
    val lines: List<TerminalLine> = object : AbstractList<TerminalLine>() {
        override val size: Int get() = scrollback.size + screen.size
        override fun get(index: Int): TerminalLine =
            if (index < scrollback.size) scrollback[index] else screen[index - scrollback.size]
    }

    fun scrollbackLines(): List<String> = scrollback.map { it.text() }

    /** Exactly [rows] strings, one per screen row. */
    fun screenLines(): List<String> = screen.map { it.text() }

    /** The screen rows without trailing blank rows. */
    fun text(): List<String> = screenLines().dropLastWhile { it.isEmpty() }
}
