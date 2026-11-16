package run.scp.mobile.app.terminal

/**
 * A terminal colour packed into an Int: 0 is "the default", otherwise the top byte tags it as a
 * palette index (0-255) or a 24-bit RGB value.
 */
object TermColor {
    const val DEFAULT = 0
    private const val TAG_INDEXED = 1
    private const val TAG_RGB = 2

    fun indexed(n: Int): Int = (TAG_INDEXED shl 24) or (n and 0xFF)
    fun rgb(r: Int, g: Int, b: Int): Int = (TAG_RGB shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isIndexed(c: Int) = (c ushr 24) == TAG_INDEXED
    fun isRgb(c: Int) = (c ushr 24) == TAG_RGB
    fun index(c: Int) = c and 0xFF
    fun rgbValue(c: Int) = c and 0xFFFFFF
}

/** A cell's look packed into a Long: 8 attribute bits, then 26 bits each of foreground and background. */
object Style {
    const val DEFAULT = 0L
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val STRIKE = 32
    const val BLINK = 64
    const val INVISIBLE = 128

    private const val COLOR_MASK = 0x3FFFFFFL

    fun make(attrs: Int, fg: Int, bg: Int): Long =
        (attrs.toLong() and 0xFF) or ((fg.toLong() and COLOR_MASK) shl 8) or ((bg.toLong() and COLOR_MASK) shl 34)

    fun attrs(s: Long): Int = (s and 0xFF).toInt()
    fun fg(s: Long): Int = ((s ushr 8) and COLOR_MASK).toInt()
    fun bg(s: Long): Int = ((s ushr 34) and COLOR_MASK).toInt()
}
