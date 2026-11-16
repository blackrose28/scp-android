package run.scp.mobile.app.terminal

/**
 * Scroll arithmetic for the terminal view. "Scrolled back" counts lines above the live screen
 * (0 = following the bottom).
 */
object Viewport {
    fun clamp(scrolledBack: Int, totalLines: Int, rows: Int): Int =
        scrolledBack.coerceIn(0, maxOf(0, totalLines - rows))

    /** Index into the snapshot's lines of the first row to draw. */
    fun firstLine(totalLines: Int, rows: Int, scrolledBack: Int): Int =
        maxOf(0, totalLines - rows - clamp(scrolledBack, totalLines, rows))

    /** Keeps the same lines in view when output grows the history; a user at the bottom stays there. */
    fun anchored(scrolledBack: Int, oldTotal: Int, newTotal: Int, rows: Int): Int =
        if (scrolledBack <= 0) 0 else clamp(scrolledBack + (newTotal - oldTotal), newTotal, rows)
}
