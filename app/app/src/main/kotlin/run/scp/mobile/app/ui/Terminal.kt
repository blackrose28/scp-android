package run.scp.mobile.app.ui

import androidx.compose.ui.input.key.Key

/**
 * Keys the app sends that are not plain text. [encode] gives the bytes an xterm would send; cursor keys
 * honour the program's application-cursor-keys mode (DECCKM) and every key takes xterm modifier parameters.
 */
enum class SpecialKey(
    private val fixed: String? = null,
    private val letter: Char? = null,
    private val number: Int? = null,
    private val alwaysSs3: Boolean = false,
) {
    Esc("\u001B"),
    Tab("\t"),
    ShiftTab("\u001B[Z"),
    CtrlC("\u0003"),
    CtrlD("\u0004"),
    Backspace("\u007F"),
    Enter("\r"),
    Up(letter = 'A'),
    Down(letter = 'B'),
    Right(letter = 'C'),
    Left(letter = 'D'),
    Home(letter = 'H'),
    End(letter = 'F'),
    Insert(number = 2),
    Delete(number = 3),
    PageUp(number = 5),
    PageDown(number = 6),
    F1(letter = 'P', alwaysSs3 = true),
    F2(letter = 'Q', alwaysSs3 = true),
    F3(letter = 'R', alwaysSs3 = true),
    F4(letter = 'S', alwaysSs3 = true),
    F5(number = 15),
    F6(number = 17),
    F7(number = 18),
    F8(number = 19),
    F9(number = 20),
    F10(number = 21),
    F11(number = 23),
    F12(number = 24);

    fun encode(
        applicationCursor: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ByteArray {
        val m = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val s = when {
            fixed != null -> if (alt && this != ShiftTab) "\u001B$fixed" else fixed
            letter != null -> when {
                m != 1 -> "\u001B[1;$m$letter"
                alwaysSs3 || applicationCursor -> "\u001BO$letter"
                else -> "\u001B[$letter"
            }
            else -> if (m == 1) "\u001B[$number~" else "\u001B[$number;$m~"
        }
        return s.toByteArray(Charsets.US_ASCII)
    }

    /** The plain (no modifiers, normal cursor mode) encoding. */
    val bytes: ByteArray get() = encode()
}

private fun controlByte(cp: Int): Int? = when (cp) {
    in 'a'.code..'z'.code, in 'A'.code..'Z'.code -> cp and 0x1F
    ' '.code, '@'.code -> 0
    '['.code -> 0x1B
    '\\'.code -> 0x1C
    ']'.code -> 0x1D
    '^'.code -> 0x1E
    '_'.code -> 0x1F
    '?'.code -> 0x7F
    else -> null
}

/** Applies sticky Ctrl/Alt to typed [text]: only its first character is affected. */
fun applyModifiers(text: String, ctrl: Boolean, alt: Boolean): ByteArray {
    if (text.isEmpty() || (!ctrl && !alt)) return text.toByteArray(Charsets.UTF_8)
    val cp = text.codePointAt(0)
    val first = String(Character.toChars(cp))
    val core = (if (ctrl) controlByte(cp) else null)?.let { byteArrayOf(it.toByte()) } ?: first.toByteArray(Charsets.UTF_8)
    val prefix = if (alt) byteArrayOf(0x1B) else ByteArray(0)
    return prefix + core + text.substring(first.length).toByteArray(Charsets.UTF_8)
}

/** Bytes for pasted [text]: newlines become CR, and in bracketed-paste mode it is wrapped (escapes stripped, so it cannot end the paste early). */
fun pasteBytes(text: String, bracketed: Boolean): ByteArray {
    if (text.isEmpty()) return ByteArray(0)
    val body = text.replace("\r\n", "\r").replace('\n', '\r')
    val out = if (bracketed) "\u001B[200~" + body.replace("\u001B", "") + "\u001B[201~" else body
    return out.toByteArray(Charsets.UTF_8)
}

private const val MAX_SCROLL_KEYS = 10

/** Arrow keys for a swipe of [lines] on the alternate screen (positive: finger moved down, so scroll up), or null. */
fun scrollKeys(lines: Int): Pair<SpecialKey, Int>? = when {
    lines > 0 -> SpecialKey.Up to minOf(lines, MAX_SCROLL_KEYS)
    lines < 0 -> SpecialKey.Down to minOf(-lines, MAX_SCROLL_KEYS)
    else -> null
}

/** The special key for a hardware key press, or null when it is text (or something we do not send). */
fun hardwareKey(key: Key, shift: Boolean): SpecialKey? = when (key) {
    Key.DirectionUp -> SpecialKey.Up
    Key.DirectionDown -> SpecialKey.Down
    Key.DirectionLeft -> SpecialKey.Left
    Key.DirectionRight -> SpecialKey.Right
    Key.MoveHome -> SpecialKey.Home
    Key.MoveEnd -> SpecialKey.End
    Key.PageUp -> SpecialKey.PageUp
    Key.PageDown -> SpecialKey.PageDown
    Key.Insert -> SpecialKey.Insert
    Key.Delete -> SpecialKey.Delete
    Key.Escape -> SpecialKey.Esc
    Key.Tab -> if (shift) SpecialKey.ShiftTab else SpecialKey.Tab
    Key.Backspace -> SpecialKey.Backspace
    Key.Enter, Key.NumPadEnter -> SpecialKey.Enter
    Key.F1 -> SpecialKey.F1
    Key.F2 -> SpecialKey.F2
    Key.F3 -> SpecialKey.F3
    Key.F4 -> SpecialKey.F4
    Key.F5 -> SpecialKey.F5
    Key.F6 -> SpecialKey.F6
    Key.F7 -> SpecialKey.F7
    Key.F8 -> SpecialKey.F8
    Key.F9 -> SpecialKey.F9
    Key.F10 -> SpecialKey.F10
    Key.F11 -> SpecialKey.F11
    Key.F12 -> SpecialKey.F12
    else -> null
}

data class TerminalSize(val cols: Int, val rows: Int)

private const val DEFAULT_COLS = 80
private const val DEFAULT_ROWS = 24
private const val MIN_COLS = 20
private const val MIN_ROWS = 5

/** Whole character cells that fit in the given pixel area. */
fun terminalSize(widthPx: Float, heightPx: Float, cellWidthPx: Float, cellHeightPx: Float): TerminalSize {
    if (cellWidthPx <= 0f || cellHeightPx <= 0f) return TerminalSize(DEFAULT_COLS, DEFAULT_ROWS)
    return TerminalSize(
        (widthPx / cellWidthPx).toInt().coerceAtLeast(MIN_COLS),
        (heightPx / cellHeightPx).toInt().coerceAtLeast(MIN_ROWS),
    )
}

/** Bytes to send for text the user just typed (with any sticky modifiers), or null when there is nothing to send or no live shell. */
fun typedBytes(text: CharSequence, connected: Boolean, ctrl: Boolean = false, alt: Boolean = false): ByteArray? =
    if (text.isEmpty() || !connected) null else applyModifiers(text.toString(), ctrl, alt)
