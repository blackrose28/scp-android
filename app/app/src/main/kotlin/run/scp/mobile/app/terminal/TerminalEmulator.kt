package run.scp.mobile.app.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * An xterm-style screen emulator: a fixed [cols]x[rows] grid of styled cells, a scrollback above it,
 * an alternate screen, scroll regions, SGR colours (16/256/true colour), DEC line drawing, wide
 * characters, and the replies (DSR, DA) that full-screen programs wait for. Scope is "vim, less,
 * htop, nano, tmux, mc render correctly", not vttest-complete: no mouse reporting, origin mode,
 * or reflow on resize.
 *
 * Input may arrive in arbitrary chunks, including mid-sequence and mid-UTF-8. Not thread-safe: the
 * owner serialises [append], [resize] and [snapshot]. [reply] receives bytes to write back to the
 * shell and is called from inside [append].
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    private val scrollbackLimit: Int = 2000,
    private val reply: (ByteArray) -> Unit = {},
) {
    var cols = cols
        private set
    var rows = rows
        private set

    init {
        require(cols >= 1 && rows >= 1) { "terminal must be at least 1x1" }
        require(scrollbackLimit >= 0) { "scrollbackLimit must be >= 0" }
    }

    // --- screens ---
    private var mainLines = blankScreen(rows)
    private var altLines = blankScreen(rows)
    private var inAlt = false
    private val lines: ArrayList<TerminalLine> get() = if (inAlt) altLines else mainLines

    private val scrollback = ArrayDeque<TerminalLine>()
    private var scrollbackCache: List<TerminalLine>? = null

    // --- cursor and pen ---
    private var row = 0
    private var col = 0
    private var wrapPending = false
    private var pen = Style.DEFAULT
    private var lastCp = 0

    private class SavedCursor(
        val row: Int, val col: Int, val pen: Long, val wrapPending: Boolean,
        val charsets: IntArray, val shifted: Boolean,
    )

    private var savedCursor: SavedCursor? = null // DECSC / CSI s
    private var altSavedCursor: SavedCursor? = null // 1049

    // --- charsets: 0 = ASCII, 1 = DEC special graphics ---
    private var charsets = intArrayOf(0, 0)
    private var shifted = false

    // --- modes ---
    private var autowrap = true
    private var insertMode = false
    private var cursorVisible = true
    private var applicationCursorKeys = false
    private var bracketedPaste = false

    // --- scroll region and tab stops ---
    private var top = 0
    private var bottom = rows - 1
    private var tabStops = defaultTabStops(cols)

    // --- parser ---
    private enum class State { GROUND, ESC, ESC_INTERMEDIATE, CSI, STRING, STRING_ESC }

    private var state = State.GROUND
    private val params = StringBuilder()
    private val intermediates = StringBuilder()
    private var marker = 0.toChar()
    private var escIntermediate = 0.toChar()

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)
    private var pendingHigh = 0.toChar()

    // ---------------------------------------------------------------- public API

    fun append(bytes: ByteArray) {
        val input = ByteBuffer.wrap(if (pendingBytes.isEmpty()) bytes else pendingBytes + bytes)
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        while (out.hasRemaining()) feedChar(out.get())
    }

    fun snapshot(): ScreenSnapshot {
        val sb: List<TerminalLine> =
            if (inAlt) emptyList() else scrollbackCache ?: ArrayList(scrollback).also { scrollbackCache = it }
        return ScreenSnapshot(
            scrollback = sb,
            screen = lines.map { it.copy() },
            cols = cols,
            rows = rows,
            cursorRow = row,
            cursorCol = col,
            cursorVisible = cursorVisible,
            applicationCursorKeys = applicationCursorKeys,
            bracketedPaste = bracketedPaste,
            alternateScreen = inAlt,
        )
    }

    fun resize(newCols: Int, newRows: Int) {
        require(newCols >= 1 && newRows >= 1) { "terminal must be at least 1x1" }
        if (newCols == cols && newRows == rows) return

        // The main screen keeps scrollback in step with the row count, so showing and hiding the
        // keyboard moves lines between the screen and scrollback instead of losing them.
        val mainCursorRow = when {
            !inAlt -> row
            else -> altSavedCursor?.row ?: (rows - 1)
        }
        val newMainCursor = resizeBuffer(mainLines, mainCursorRow, newRows, newCols, useScrollback = true)
        resizeBuffer(altLines, if (inAlt) row else 0, newRows, newCols, useScrollback = false).let {
            if (inAlt) row = it
        }
        if (inAlt) altSavedCursor = altSavedCursor?.let { moved(it, newMainCursor, newCols) }
        else row = newMainCursor
        savedCursor = savedCursor?.let { moved(it, it.row.coerceAtMost(newRows - 1), newCols) }

        cols = newCols
        rows = newRows
        col = col.coerceAtMost(newCols - 1)
        row = row.coerceIn(0, newRows - 1)
        wrapPending = false
        top = 0
        bottom = newRows - 1
        tabStops = defaultTabStops(newCols)
        scrollbackCache = null // scrollback keeps the widths it was written with
    }

    // ---------------------------------------------------------------- helpers: buffers

    private fun blankScreen(rows: Int) = ArrayList<TerminalLine>(rows).apply { repeat(rows) { add(TerminalLine(cols)) } }

    private fun defaultTabStops(cols: Int) = BooleanArray(cols) { it % 8 == 0 }

    private fun moved(s: SavedCursor, newRow: Int, newCols: Int) =
        SavedCursor(newRow, s.col.coerceAtMost(newCols - 1), s.pen, false, s.charsets, s.shifted)

    /** Resizes one screen buffer in place and returns where its cursor row ends up. */
    private fun resizeBuffer(buf: ArrayList<TerminalLine>, cursorRow: Int, newRows: Int, newCols: Int, useScrollback: Boolean): Int {
        var cursor = cursorRow
        val oldRows = buf.size
        if (newRows < oldRows) {
            val dropTop = maxOf(0, cursor - (newRows - 1))
            repeat(dropTop) {
                val line = buf.removeAt(0)
                if (useScrollback) pushScrollback(line)
            }
            cursor -= dropTop
            while (buf.size > newRows) buf.removeAt(buf.size - 1)
        } else if (newRows > oldRows) {
            val pull = if (useScrollback) minOf(newRows - oldRows, scrollback.size) else 0
            repeat(pull) { buf.add(0, scrollback.removeLast()) }
            if (pull > 0) scrollbackCache = null
            cursor += pull
            while (buf.size < newRows) buf.add(TerminalLine(newCols))
        }
        for (i in buf.indices) buf[i] = buf[i].resized(newCols)
        return cursor.coerceIn(0, newRows - 1)
    }

    private fun pushScrollback(line: TerminalLine) {
        if (scrollbackLimit == 0) return
        scrollback.addLast(line)
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        scrollbackCache = null
    }

    /** A blank cell keeps the pen's background so full-screen programs can paint with erases. */
    private fun eraseStyle() = Style.make(0, 0, Style.bg(pen))

    private fun blankLine() = TerminalLine(cols, eraseStyle())

    // ---------------------------------------------------------------- parser

    private fun feedChar(c: Char) {
        val cp = when {
            Character.isHighSurrogate(c) -> {
                if (pendingHigh != 0.toChar()) feed(0xFFFD)
                pendingHigh = c
                return
            }
            Character.isLowSurrogate(c) -> {
                if (pendingHigh == 0.toChar()) {
                    0xFFFD
                } else {
                    Character.toCodePoint(pendingHigh, c).also { pendingHigh = 0.toChar() }
                }
            }
            else -> {
                if (pendingHigh != 0.toChar()) {
                    pendingHigh = 0.toChar()
                    feed(0xFFFD)
                }
                c.code
            }
        }
        feed(cp)
    }

    private fun feed(cp: Int) {
        when (state) {
            State.GROUND -> ground(cp)
            State.ESC -> escape(cp)
            State.ESC_INTERMEDIATE -> when {
                cp == 0x1B -> startEscape()
                cp < 0x20 -> control(cp)
                cp in 0x20..0x2F -> Unit // more intermediates
                else -> { escDesignate(cp); state = State.GROUND }
            }
            State.CSI -> csiByte(cp)
            State.STRING -> when (cp) {
                0x07 -> state = State.GROUND
                0x1B -> state = State.STRING_ESC
                0x18, 0x1A -> state = State.GROUND
            }
            State.STRING_ESC -> state = when (cp) {
                '\\'.code -> State.GROUND // ST
                0x1B -> State.STRING_ESC
                else -> State.STRING
            }
        }
    }

    private fun startEscape() {
        state = State.ESC
    }

    private fun ground(cp: Int) {
        when {
            cp == 0x1B -> startEscape()
            cp < 0x20 -> control(cp)
            cp == 0x7F || cp in 0x80..0x9F -> Unit
            else -> put(cp)
        }
    }

    /** C0 controls; they act in every parser state except those that handle ESC/CAN themselves. */
    private fun control(cp: Int) {
        when (cp) {
            0x08 -> { if (col > 0) col--; wrapPending = false }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> { index(); wrapPending = false }
            0x0D -> { col = 0; wrapPending = false }
            0x0E -> shifted = true
            0x0F -> shifted = false
            0x18, 0x1A -> state = State.GROUND
        }
    }

    private fun escape(cp: Int) {
        state = State.GROUND
        when (cp.toChar()) {
            '[' -> { state = State.CSI; params.setLength(0); intermediates.setLength(0); marker = 0.toChar() }
            ']', 'P', 'X', '^', '_' -> state = State.STRING
            '(', ')', '*', '+', '#', '%', ' ' -> { escIntermediate = cp.toChar(); state = State.ESC_INTERMEDIATE }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> { index(); wrapPending = false }
            'E' -> { index(); col = 0; wrapPending = false }
            'M' -> { reverseIndex(); wrapPending = false }
            'H' -> if (col < tabStops.size) tabStops[col] = true
            'c' -> fullReset()
            else -> if (cp == 0x1B) startEscape() else if (cp < 0x20) control(cp)
        }
    }

    private fun escDesignate(final: Int) {
        val set = if (final == '0'.code) 1 else 0
        when (escIntermediate) {
            '(' -> charsets[0] = set
            ')' -> charsets[1] = set
        }
    }

    private fun csiByte(cp: Int) {
        when {
            cp == 0x1B -> startEscape()
            cp < 0x20 -> control(cp)
            cp in 0x30..0x3F -> {
                if (params.isEmpty() && marker == 0.toChar() && cp.toChar() in "<=>?") marker = cp.toChar()
                else params.append(cp.toChar())
            }
            cp in 0x20..0x2F -> intermediates.append(cp.toChar())
            cp in 0x40..0x7E -> { state = State.GROUND; csi(cp.toChar()) }
            else -> state = State.GROUND // malformed: abandon the sequence
        }
    }

    // ---------------------------------------------------------------- CSI dispatch

    private fun group(i: Int): String = params.split(';').getOrNull(i).orEmpty()

    /** The i-th parameter, or [default] when absent or empty. */
    private fun param(i: Int, default: Int): Int = group(i).substringBefore(':').toIntOrNull() ?: default

    /** The i-th parameter as a count: absent, empty or 0 means 1. */
    private fun count(i: Int): Int = param(i, 1).let { if (it <= 0) 1 else it }

    private fun csi(final: Char) {
        if (marker == '?') {
            if (final == 'h' || final == 'l') {
                for (g in params.split(';')) g.toIntOrNull()?.let { privateMode(it, final == 'h') }
            }
            return
        }
        if (marker == '>') {
            if (final == 'c') send("\u001B[>0;0;0c")
            return
        }
        if (marker != 0.toChar()) return
        if (intermediates.isNotEmpty()) {
            if (intermediates.toString() == "!" && final == 'p') softReset()
            return
        }
        when (final) {
            'A' -> { row = maxOf(row - count(0), if (row >= top) top else 0); wrapPending = false }
            'B', 'e' -> { row = minOf(row + count(0), if (row <= bottom) bottom else rows - 1); wrapPending = false }
            'C', 'a' -> { col = minOf(col + count(0), cols - 1); wrapPending = false }
            'D' -> { col = maxOf(col - count(0), 0); wrapPending = false }
            'E' -> { row = minOf(row + count(0), if (row <= bottom) bottom else rows - 1); col = 0; wrapPending = false }
            'F' -> { row = maxOf(row - count(0), if (row >= top) top else 0); col = 0; wrapPending = false }
            'G', '`' -> { col = (count(0) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'd' -> { row = (count(0) - 1).coerceIn(0, rows - 1); wrapPending = false }
            'H', 'f' -> {
                row = (count(0) - 1).coerceIn(0, rows - 1)
                col = (count(1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'I' -> tab(count(0))
            'Z' -> backTab(count(0))
            'J' -> eraseDisplay(param(0, 0))
            'K' -> eraseLine(param(0, 0))
            'L' -> if (row in top..bottom) { insertLines(count(0)); col = 0; wrapPending = false }
            'M' -> if (row in top..bottom) { deleteLines(count(0)); col = 0; wrapPending = false }
            '@' -> insertChars(count(0))
            'P' -> deleteChars(count(0))
            'X' -> eraseCells(col, minOf(col + count(0), cols))
            'S' -> scrollUp(count(0))
            'T' -> if (!params.contains(';')) scrollDown(count(0))
            'b' -> if (lastCp != 0) repeat(minOf(count(0), 65535)) { put(lastCp) }
            'g' -> when (param(0, 0)) {
                0 -> if (col < tabStops.size) tabStops[col] = false
                3 -> tabStops.fill(false)
            }
            'h', 'l' -> for (g in params.split(';')) if (g.toIntOrNull() == 4) insertMode = final == 'h'
            'm' -> sgr()
            'n' -> when (param(0, 0)) {
                5 -> send("\u001B[0n")
                6 -> send("\u001B[${row + 1};${col + 1}R")
            }
            'c' -> if (param(0, 0) == 0) send("\u001B[?62;22c")
            'r' -> setScrollRegion(param(0, 1), param(1, rows))
            's' -> if (params.isEmpty()) saveCursor()
            'u' -> if (params.isEmpty()) restoreCursor()
        }
    }

    private fun send(s: String) = reply(s.toByteArray(Charsets.UTF_8))

    private fun privateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            7 -> { autowrap = on; if (!on) wrapPending = false }
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, saveCursor = false)
            1049 -> switchScreen(on, saveCursor = true)
            2004 -> bracketedPaste = on
        }
    }

    private fun switchScreen(toAlt: Boolean, saveCursor: Boolean) {
        if (toAlt == inAlt) return
        if (toAlt) {
            if (saveCursor) altSavedCursor = snapshotCursor()
            inAlt = true
            altLines = blankScreen(rows)
        } else {
            inAlt = false
            if (saveCursor) altSavedCursor?.let { applyCursor(it) }
            altSavedCursor = null
        }
        top = 0
        bottom = rows - 1
    }

    // ---------------------------------------------------------------- cursor save/restore, resets

    private fun snapshotCursor() = SavedCursor(row, col, pen, wrapPending, charsets.copyOf(), shifted)

    private fun applyCursor(s: SavedCursor) {
        row = s.row.coerceIn(0, rows - 1)
        col = s.col.coerceIn(0, cols - 1)
        pen = s.pen
        wrapPending = s.wrapPending
        charsets = s.charsets.copyOf()
        shifted = s.shifted
    }

    private fun saveCursor() {
        savedCursor = snapshotCursor()
    }

    private fun restoreCursor() {
        savedCursor?.let { applyCursor(it) } ?: run { row = 0; col = 0; wrapPending = false; pen = Style.DEFAULT }
    }

    private fun softReset() {
        autowrap = true
        insertMode = false
        cursorVisible = true
        applicationCursorKeys = false
        bracketedPaste = false
        pen = Style.DEFAULT
        charsets = intArrayOf(0, 0)
        shifted = false
        savedCursor = null
        top = 0
        bottom = rows - 1
        wrapPending = false
    }

    private fun fullReset() {
        softReset()
        inAlt = false
        altSavedCursor = null
        mainLines = blankScreen(rows)
        altLines = blankScreen(rows)
        tabStops = defaultTabStops(cols)
        row = 0
        col = 0
        lastCp = 0
        state = State.GROUND
    }

    // ---------------------------------------------------------------- SGR

    private fun sgr() {
        val groups = if (params.isEmpty()) listOf("0") else params.split(';')
        var attrs = Style.attrs(pen)
        var fg = Style.fg(pen)
        var bg = Style.bg(pen)
        var i = 0
        while (i < groups.size) {
            val g = groups[i]
            if (':' in g) {
                val sub = g.split(':').map { it.toIntOrNull() }
                when (sub[0]) {
                    38 -> extendedColor(sub.drop(1))?.let { fg = it }
                    48 -> extendedColor(sub.drop(1))?.let { bg = it }
                    4 -> attrs = if (sub.getOrNull(1) == 0) attrs and Style.UNDERLINE.inv() else attrs or Style.UNDERLINE
                }
                i++
                continue
            }
            when (val n = g.toIntOrNull() ?: 0) {
                0 -> { attrs = 0; fg = 0; bg = 0 }
                1 -> attrs = attrs or Style.BOLD
                2 -> attrs = attrs or Style.DIM
                3 -> attrs = attrs or Style.ITALIC
                4, 21 -> attrs = attrs or Style.UNDERLINE
                5, 6 -> attrs = attrs or Style.BLINK
                7 -> attrs = attrs or Style.INVERSE
                8 -> attrs = attrs or Style.INVISIBLE
                9 -> attrs = attrs or Style.STRIKE
                22 -> attrs = attrs and (Style.BOLD or Style.DIM).inv()
                23 -> attrs = attrs and Style.ITALIC.inv()
                24 -> attrs = attrs and Style.UNDERLINE.inv()
                25 -> attrs = attrs and Style.BLINK.inv()
                27 -> attrs = attrs and Style.INVERSE.inv()
                28 -> attrs = attrs and Style.INVISIBLE.inv()
                29 -> attrs = attrs and Style.STRIKE.inv()
                in 30..37 -> fg = TermColor.indexed(n - 30)
                38, 48 -> {
                    val rest = groups.drop(i + 1).map { it.toIntOrNull() }
                    val mode = rest.getOrNull(0)
                    val color: Int?
                    when (mode) {
                        5 -> { color = rest.getOrNull(1)?.let { TermColor.indexed(it) }; i += 2 }
                        2 -> {
                            color = extendedColor(rest.take(4)); i += 4
                        }
                        else -> color = null
                    }
                    if (color != null) if (n == 38) fg = color else bg = color
                }
                39 -> fg = 0
                in 40..47 -> bg = TermColor.indexed(n - 40)
                49 -> bg = 0
                in 90..97 -> fg = TermColor.indexed(n - 90 + 8)
                in 100..107 -> bg = TermColor.indexed(n - 100 + 8)
            }
            i++
        }
        pen = Style.make(attrs, fg, bg)
    }

    /** Colour from the tail of a `38`/`48` parameter: `5;n`, `2;r;g;b`, or colon forms with an optional colour-space id. */
    private fun extendedColor(rest: List<Int?>): Int? = when (rest.getOrNull(0)) {
        5 -> rest.getOrNull(1)?.let { TermColor.indexed(it) }
        2 -> {
            val rgb = rest.drop(1)
            val c = if (rgb.size >= 4) rgb.takeLast(3) else rgb.take(3)
            if (c.size == 3 && c.all { it != null }) TermColor.rgb(c[0]!!, c[1]!!, c[2]!!) else null
        }
        else -> null
    }

    // ---------------------------------------------------------------- printing

    private fun put(rawCp: Int) {
        var cp = rawCp
        val gl = if (shifted) charsets[1] else charsets[0]
        if (gl == 1 && cp in 0x60..0x7E) cp = GRAPHICS[cp - 0x60].code
        val w = CellWidth.of(cp)
        if (w == 0) {
            combine(cp)
            return
        }
        if (cols < w) return
        if (wrapPending && autowrap) {
            col = 0
            index()
        }
        wrapPending = false
        if (col + w > cols) {
            if (autowrap) {
                lines[row].let { clearWide(it, col, col + 1); it.cps[col] = 0 }
                col = 0
                index()
            } else {
                col = cols - w
            }
        }
        val line = lines[row]
        if (insertMode) shiftRight(line, col, w)
        clearWide(line, col, col + w)
        line.cps[col] = cp
        line.styles[col] = pen
        if (w == 2) {
            line.cps[col + 1] = TerminalLine.SPACER
            line.styles[col + 1] = pen
        }
        lastCp = cp
        col += w
        if (col >= cols) {
            col = cols - 1
            wrapPending = autowrap
        }
    }

    /** Attaches a combining mark to the previous character when a precomposed form exists, else drops it. */
    private fun combine(cp: Int) {
        var target = if (wrapPending) col else col - 1
        if (target < 0) return
        val line = lines[row]
        if (line.cps[target] == TerminalLine.SPACER && target > 0) target--
        val base = line.cps[target]
        if (base <= 0) return
        val composed = Normalizer.normalize(String(Character.toChars(base)) + String(Character.toChars(cp)), Normalizer.Form.NFC)
        if (composed.codePointCount(0, composed.length) == 1) line.cps[target] = composed.codePointAt(0)
    }

    /** Blanks the other half of any wide character that overwriting cells [from, to) would cut in two. */
    private fun clearWide(line: TerminalLine, from: Int, to: Int) {
        if (from > 0 && from < line.size && line.cps[from] == TerminalLine.SPACER) line.cps[from - 1] = 0
        if (to < line.size && line.cps[to] == TerminalLine.SPACER) line.cps[to] = 0
    }

    private fun tab(n: Int) {
        repeat(n) {
            var c = col + 1
            while (c < cols - 1 && !tabStops[c]) c++
            col = minOf(c, cols - 1)
        }
        wrapPending = false
    }

    private fun backTab(n: Int) {
        repeat(n) {
            var c = col - 1
            while (c > 0 && !tabStops[c]) c--
            col = maxOf(c, 0)
        }
        wrapPending = false
    }

    // ---------------------------------------------------------------- scrolling

    private fun index() {
        if (row == bottom) scrollUp(1) else if (row < rows - 1) row++
    }

    private fun reverseIndex() {
        if (row == top) scrollDown(1) else if (row > 0) row--
    }

    private fun scrollUp(n: Int) {
        val feedsScrollback = !inAlt && top == 0 && bottom == rows - 1
        repeat(minOf(n, bottom - top + 1)) {
            val gone = lines.removeAt(top)
            if (feedsScrollback) pushScrollback(gone)
            lines.add(bottom, blankLine())
        }
    }

    private fun scrollDown(n: Int) {
        repeat(minOf(n, bottom - top + 1)) {
            lines.removeAt(bottom)
            lines.add(top, blankLine())
        }
    }

    private fun setScrollRegion(t: Int, b: Int) {
        val newTop = (if (t <= 0) 1 else t) - 1
        val newBottom = (if (b <= 0) rows else minOf(b, rows)) - 1
        if (newTop >= newBottom) return
        top = newTop
        bottom = newBottom
        row = 0
        col = 0
        wrapPending = false
    }

    private fun insertLines(n: Int) {
        repeat(minOf(n, bottom - row + 1)) {
            lines.removeAt(bottom)
            lines.add(row, blankLine())
        }
    }

    private fun deleteLines(n: Int) {
        repeat(minOf(n, bottom - row + 1)) {
            lines.removeAt(row)
            lines.add(bottom, blankLine())
        }
    }

    // ---------------------------------------------------------------- erasing and editing

    private fun eraseCells(from: Int, to: Int) {
        val line = lines[row]
        val end = minOf(to, line.size)
        if (from >= end) return
        clearWide(line, from, end)
        val style = eraseStyle()
        for (i in from until end) {
            line.cps[i] = 0
            line.styles[i] = style
        }
    }

    private fun eraseRows(from: Int, to: Int) {
        for (r in from until to) lines[r] = blankLine()
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseCells(col, cols)
            1 -> eraseCells(0, col + 1)
            2 -> eraseCells(0, cols)
        }
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseCells(col, cols); eraseRows(row + 1, rows) }
            1 -> { eraseRows(0, row); eraseCells(0, col + 1) }
            2 -> eraseRows(0, rows)
            3 -> { scrollback.clear(); scrollbackCache = null }
        }
        wrapPending = false
    }

    private fun insertChars(n: Int) {
        val line = lines[row]
        shiftRight(line, col, minOf(n, cols - col))
        wrapPending = false
    }

    private fun shiftRight(line: TerminalLine, at: Int, n: Int) {
        if (n <= 0 || at >= line.size) return
        val moved = line.size - at - n
        if (moved > 0) {
            System.arraycopy(line.cps, at, line.cps, at + n, moved)
            System.arraycopy(line.styles, at, line.styles, at + n, moved)
        }
        // A wide character whose right half was pushed off the edge leaves a stray start cell.
        val last = line.size - 1
        if (line.cps[last] > 0 && CellWidth.of(line.cps[last]) == 2) line.cps[last] = 0
        clearWide(line, at, at + n)
        for (i in at until minOf(at + n, line.size)) {
            line.cps[i] = 0
            line.styles[i] = eraseStyle()
        }
    }

    private fun deleteChars(n: Int) {
        val line = lines[row]
        val k = minOf(n, cols - col)
        clearWide(line, col, col + k)
        val moved = cols - col - k
        if (moved > 0) {
            System.arraycopy(line.cps, col + k, line.cps, col, moved)
            System.arraycopy(line.styles, col + k, line.styles, col, moved)
        }
        val style = eraseStyle()
        for (i in cols - k until cols) {
            line.cps[i] = 0
            line.styles[i] = style
        }
        if (col > 0 && col < cols && line.cps[col] == TerminalLine.SPACER) line.cps[col - 1] = 0
        wrapPending = false
    }

    private companion object {
        /** DEC special graphics for 0x60..0x7E. */
        const val GRAPHICS = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}
