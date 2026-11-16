package run.scp.mobile.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import run.scp.mobile.app.terminal.Palette
import run.scp.mobile.app.terminal.ScreenSnapshot
import run.scp.mobile.app.terminal.Style
import run.scp.mobile.app.terminal.TerminalLine

internal const val TERMINAL_FG = 0xFFE0E0E0.toInt()
internal const val TERMINAL_BG = 0xFF101010.toInt()

/** Paints and the character-cell metrics derived from one monospace font size. */
internal class TerminalPaints(textSizePx: Float) {
    val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = textSizePx }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = textSizePx
    }
    val fill = Paint()
    val cellWidth: Float = regular.measureText("M")
    val ascent: Float = -regular.fontMetrics.ascent
    val cellHeight: Float = ceil(regular.fontMetrics.descent - regular.fontMetrics.ascent)
}

private class CellColors(var fg: Int = TERMINAL_FG, var bg: Int = TERMINAL_BG)

private fun resolve(style: Long, out: CellColors) {
    val attrs = Style.attrs(style)
    var fg = Palette.argb(Style.fg(style), TERMINAL_FG, bold = attrs and Style.BOLD != 0)
    var bg = Palette.argb(Style.bg(style), TERMINAL_BG)
    if (attrs and Style.INVERSE != 0) { val t = fg; fg = bg; bg = t }
    if (attrs and Style.DIM != 0) fg = blend(fg, bg, 0.6f)
    out.fg = fg
    out.bg = bg
}

private fun blend(fg: Int, bg: Int, amount: Float): Int {
    fun ch(shift: Int) = (((fg ushr shift) and 0xFF) * amount + ((bg ushr shift) and 0xFF) * (1 - amount)).toInt()
    return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * Draws [rows] lines of [snap] starting at line [first] on a fixed cell grid, so a program's idea of
 * the screen (cursor addressing, full-width bars) lines up exactly. ASCII runs are drawn as strings;
 * anything else is placed cell by cell so fallback fonts cannot drift the columns.
 */
internal fun drawScreen(c: Canvas, snap: ScreenSnapshot, first: Int, p: TerminalPaints, drawCursor: Boolean) {
    c.drawColor(TERMINAL_BG)
    val colors = CellColors()
    val cw = p.cellWidth
    val ch = p.cellHeight
    for (r in 0 until snap.rows) {
        val index = first + r
        if (index >= snap.lines.size) break
        drawLine(c, snap.lines[index], minOf(snap.cols, snap.lines[index].size), r * ch, p, colors)
    }
    if (drawCursor && snap.cursorVisible) {
        val lineIndex = snap.screenTop + snap.cursorRow
        if (lineIndex in first until first + snap.rows) {
            val x = snap.cursorCol * cw
            val y = (lineIndex - first) * ch
            p.fill.color = TERMINAL_FG
            c.drawRect(x, y, x + cw, y + ch, p.fill)
            val line = snap.lines[lineIndex]
            val cp = if (snap.cursorCol < line.size) line.cps[snap.cursorCol] else 0
            if (cp > 0x20) {
                p.regular.color = TERMINAL_BG
                c.drawText(String(Character.toChars(cp)), x, y + p.ascent, p.regular)
            }
        }
    }
}

private fun drawLine(c: Canvas, line: TerminalLine, cells: Int, top: Float, p: TerminalPaints, colors: CellColors) {
    val cw = p.cellWidth
    val ch = p.cellHeight

    // Backgrounds: merge neighbouring cells with the same colour into one rectangle.
    var runStart = 0
    var runBg = TERMINAL_BG
    for (i in 0..cells) {
        val bg = if (i < cells) { resolve(line.styles[i], colors); colors.bg } else TERMINAL_BG
        if (i == cells || bg != runBg) {
            if (runBg != TERMINAL_BG && i > runStart) {
                p.fill.color = runBg
                c.drawRect(runStart * cw, top, i * cw, top + ch, p.fill)
            }
            runStart = i
            runBg = bg
        }
    }

    // Text: runs of ASCII with identical look; other characters one cell at a time.
    val baseline = top + p.ascent
    val run = StringBuilder()
    var runX = 0
    var runStyle = 0L
    var runFg = 0
    fun flush(end: Int) {
        if (run.isNotEmpty()) {
            val attrs = Style.attrs(runStyle)
            val decorated = attrs and (Style.UNDERLINE or Style.STRIKE) != 0
            if (decorated || run.any { it != ' ' }) {
                val paint = if (attrs and Style.BOLD != 0) p.bold else p.regular
                paint.color = runFg
                paint.textSkewX = if (attrs and Style.ITALIC != 0) -0.25f else 0f
                if (run.any { it != ' ' }) c.drawText(run, 0, run.length, runX * cw, baseline, paint)
                p.fill.color = runFg
                if (attrs and Style.UNDERLINE != 0) {
                    c.drawRect(runX * cw, baseline + 2f, end * cw, baseline + 2f + maxOf(1f, ch / 16f), p.fill)
                }
                if (attrs and Style.STRIKE != 0) {
                    val y = top + ch / 2f
                    c.drawRect(runX * cw, y, end * cw, y + maxOf(1f, ch / 16f), p.fill)
                }
            }
            run.setLength(0)
        }
    }
    for (i in 0 until cells) {
        val cp = line.cps[i]
        if (cp == TerminalLine.SPACER) continue
        val style = line.styles[i]
        resolve(style, colors)
        val invisible = Style.attrs(style) and Style.INVISIBLE != 0
        val look = style and Style.make(Style.BOLD or Style.ITALIC or Style.UNDERLINE or Style.STRIKE, 0, 0)
        if (run.isNotEmpty() && (look != runStyle || colors.fg != runFg)) flush(i)
        val ascii = cp == 0 || cp in 0x20..0x7E
        if (ascii || invisible) {
            if (run.isEmpty()) { runX = i; runStyle = look; runFg = colors.fg }
            run.append(if (invisible || cp == 0) ' ' else cp.toChar())
        } else {
            flush(i)
            val paint = if (Style.attrs(style) and Style.BOLD != 0) p.bold else p.regular
            paint.color = colors.fg
            paint.textSkewX = if (Style.attrs(style) and Style.ITALIC != 0) -0.25f else 0f
            c.drawText(String(Character.toChars(cp)), i * cw, baseline, paint)
        }
    }
    flush(cells)
    p.regular.textSkewX = 0f
    p.bold.textSkewX = 0f
}
