package run.scp.mobile.app.terminal

/** Turns a [TermColor] into an opaque ARGB int using the xterm 256-colour layout. */
object Palette {
    fun argb(color: Int, default: Int, bold: Boolean = false): Int = when {
        TermColor.isRgb(color) -> 0xFF000000.toInt() or TermColor.rgbValue(color)
        TermColor.isIndexed(color) -> {
            val i = TermColor.index(color)
            TABLE[if (bold && i < 8) i + 8 else i]
        }
        else -> default
    }

    private val ANSI = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )
    private val LEVELS = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)

    private val TABLE = IntArray(256) { i ->
        0xFF000000.toInt() or when {
            i < 16 -> ANSI[i]
            i < 232 -> {
                val n = i - 16
                (LEVELS[n / 36] shl 16) or (LEVELS[n / 6 % 6] shl 8) or LEVELS[n % 6]
            }
            else -> (8 + 10 * (i - 232)).let { (it shl 16) or (it shl 8) or it }
        }
    }
}
