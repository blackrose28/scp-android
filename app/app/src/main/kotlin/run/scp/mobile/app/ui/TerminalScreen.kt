package run.scp.mobile.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import run.scp.mobile.app.session.SessionState
import run.scp.mobile.app.session.TerminalSnapshot
import run.scp.mobile.app.terminal.Viewport

private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 28
private const val DEFAULT_FONT_SP = 13

@Composable
fun TerminalScreen(
    state: SessionState,
    terminal: TerminalSnapshot,
    onSend: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onClose: () -> Unit,
) {
    val connected = state is SessionState.Connected
    val screen = terminal.screen
    val density = LocalDensity.current
    var fontSp by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_SP) }
    val paints = remember(fontSp, density) { TerminalPaints(with(density) { fontSp.sp.toPx() }) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Lines above the live screen the user has scrolled back; 0 follows the output.
    var scrolledBack by remember { mutableIntStateOf(0) }
    var lastTotal by remember { mutableIntStateOf(screen.lines.size) }
    LaunchedEffect(terminal.revision) {
        val total = screen.lines.size
        scrolledBack = if (screen.alternateScreen) 0 else Viewport.anchored(scrolledBack, lastTotal, total, screen.rows)
        lastTotal = total
    }
    LaunchedEffect(connected) { if (connected) focus.requestFocus() }

    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val app = screen.applicationCursorKeys

    /** Everything the user types goes through here: it returns to the live screen and clears sticky modifiers. */
    fun send(bytes: ByteArray) {
        scrolledBack = 0
        onSend(bytes)
    }

    fun sendKey(key: SpecialKey, ctrlHeld: Boolean = ctrl, altHeld: Boolean = alt, shift: Boolean = false) {
        send(key.encode(applicationCursor = app, ctrl = ctrlHeld, alt = altHeld, shift = shift))
        ctrl = false
        alt = false
    }

    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun paste() {
        scope.launch {
            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty() && connected) send(pasteBytes(text, screen.bracketedPaste))
        }
    }

    Column(Modifier.fillMaxSize().background(Color(TERMINAL_BG)).safeDrawingPadding().imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
            val size = with(density) {
                terminalSize(maxWidth.toPx(), maxHeight.toPx(), paints.cellWidth, paints.cellHeight)
            }
            LaunchedEffect(size, connected) { if (connected) onResize(size.cols, size.rows) }

            // Normal screen: a swipe scrolls the history. Alternate screen (less, vim, man): there is no
            // history, so a swipe becomes arrow keys, like most terminal apps do.
            val latestScreen by rememberUpdatedState(screen)
            val latestConnected by rememberUpdatedState(connected)
            val remainder = remember { floatArrayOf(0f) }
            val scrollState = rememberScrollableState { delta ->
                val s = latestScreen
                remainder[0] += delta
                val lines = (remainder[0] / paints.cellHeight).toInt()
                if (lines != 0) {
                    remainder[0] -= lines * paints.cellHeight
                    if (s.alternateScreen) {
                        if (latestConnected) scrollKeys(lines)?.let { (key, n) ->
                            repeat(n) { onSend(key.encode(applicationCursor = s.applicationCursorKeys)) }
                        }
                    } else {
                        scrolledBack = Viewport.clamp(scrolledBack + lines, s.lines.size, s.rows)
                    }
                }
                delta
            }

            val back = if (screen.alternateScreen) 0 else Viewport.clamp(scrolledBack, screen.lines.size, screen.rows)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .scrollable(scrollState, Orientation.Vertical)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focus.requestFocus()
                            keyboard?.show()
                        })
                    },
            ) {
                drawIntoCanvas { canvas ->
                    drawScreen(
                        canvas.nativeCanvas, screen, Viewport.firstLine(screen.lines.size, screen.rows, back),
                        paints, drawCursor = back == 0 && connected,
                    )
                }
            }
        }

        when (state) {
            is SessionState.Ended ->
                Text(
                    "Session ended" + (state.reason?.let { ": $it" } ?: ""),
                    color = Color(0xFFFFB74D), modifier = Modifier.padding(8.dp),
                )
            is SessionState.Failed -> Text(state.message, color = Color(0xFFFF8A80), modifier = Modifier.padding(8.dp))
            else -> Unit
        }

        // Typed characters are sent immediately and the edit is reverted, so the field never holds text. The
        // value-based overload (`value = ""`) dropped repeated characters ("ooo" arrived as nothing).
        val inputState = rememberTextFieldState()
        BasicTextField(
            state = inputState,
            enabled = connected,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = {
                typedBytes(asCharSequence(), connected, ctrl, alt)?.let { bytes ->
                    send(bytes)
                    ctrl = false
                    alt = false
                }
                revertAllChanges()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Send,
            ),
            onKeyboardAction = { sendKey(SpecialKey.Enter) },
            modifier = Modifier
                .alpha(0f)
                .height(1.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || !connected) return@onPreviewKeyEvent false
                    val special = hardwareKey(e.key, e.isShiftPressed)
                    if (special != null) {
                        sendKey(special, ctrl || e.isCtrlPressed, alt || e.isAltPressed, e.isShiftPressed)
                        return@onPreviewKeyEvent true
                    }
                    // Hardware Ctrl/Alt chords: the text field would drop or mangle them.
                    if ((e.isCtrlPressed || e.isAltPressed) && !e.isMetaPressed) {
                        val cp = e.nativeKeyEvent.getUnicodeChar(if (e.isShiftPressed) android.view.KeyEvent.META_SHIFT_ON else 0)
                        if (cp > 0) {
                            send(applyModifiers(String(Character.toChars(cp)), e.isCtrlPressed, e.isAltPressed))
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                },
        )

        KeyRow(
            enabled = connected, ctrl = ctrl, alt = alt,
            onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt },
            onKey = ::sendKey,
            onText = { text -> send(applyModifiers(text, ctrl, alt)); ctrl = false; alt = false },
            onPaste = ::paste,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                TextButton(onClick = { fontSp = (fontSp - 1).coerceAtLeast(MIN_FONT_SP) }, enabled = fontSp > MIN_FONT_SP) { Text("A−") }
                TextButton(onClick = { fontSp = (fontSp + 1).coerceAtMost(MAX_FONT_SP) }, enabled = fontSp < MAX_FONT_SP) { Text("A+") }
            }
            TextButton(onClick = onClose) { Text(if (connected) "Disconnect" else "Close") }
        }
    }
}

@Composable
private fun KeyRow(
    enabled: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (SpecialKey) -> Unit,
    onText: (String) -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun key(label: String, width: Int = 46, onClick: () -> Unit) = OutlinedButton(
            onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(width.dp),
        ) { Text(label, fontSize = 12.sp, maxLines = 1) }

        @Composable
        fun toggle(label: String, active: Boolean, onClick: () -> Unit) {
            val modifier = Modifier.width(46.dp)
            if (active) {
                Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = modifier) {
                    Text(label, fontSize = 12.sp, maxLines = 1)
                }
            } else {
                OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = modifier) {
                    Text(label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }

        key("Esc") { onKey(SpecialKey.Esc) }
        key("Tab") { onKey(SpecialKey.Tab) }
        toggle("Ctrl", ctrl, onCtrl)
        toggle("Alt", alt, onAlt)
        key("↑") { onKey(SpecialKey.Up) }
        key("↓") { onKey(SpecialKey.Down) }
        key("←") { onKey(SpecialKey.Left) }
        key("→") { onKey(SpecialKey.Right) }
        key("Home") { onKey(SpecialKey.Home) }
        key("End") { onKey(SpecialKey.End) }
        key("PgUp") { onKey(SpecialKey.PageUp) }
        key("PgDn") { onKey(SpecialKey.PageDown) }
        key("^C") { onKey(SpecialKey.CtrlC) }
        key("^D") { onKey(SpecialKey.CtrlD) }
        for (symbol in listOf("|", "~", "/", "-", "_")) key(symbol) { onText(symbol) }
        key("Paste", width = 56, onClick = onPaste)
    }
}
