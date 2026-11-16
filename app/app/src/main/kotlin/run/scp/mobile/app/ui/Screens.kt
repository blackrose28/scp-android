package run.scp.mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import run.scp.mobile.app.session.Route
import run.scp.mobile.app.session.SessionState
import run.scp.mobile.app.session.SshTarget
import run.scp.mobile.app.session.TerminalSnapshot
import run.scp.mobile.app.tofu.HostKeyChallenge

@Composable
fun ConnectScreen(
    publicKey: String,
    error: String?,
    onConnect: (SshTarget) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var viaBoundary by remember { mutableStateOf(false) }
    var controllerUrl by remember { mutableStateOf("") }
    var authMethodId by remember { mutableStateOf("") }
    var targetId by remember { mutableStateOf("") }
    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val boundary = boundaryRoute(controllerUrl, authMethodId, targetId)
    val canConnect = host.isNotBlank() && user.isNotBlank() && portNum != null && (!viaBoundary || boundary != null)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect", style = MaterialTheme.typography.headlineMedium)
        if (onBack != null) TextButton(onClick = onBack) { Text("← Servers") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !viaBoundary, onClick = { viaBoundary = false }, label = { Text("Direct") })
            FilterChip(selected = viaBoundary, onClick = { viaBoundary = true }, label = { Text("Boundary") })
        }
        if (viaBoundary) {
            OutlinedTextField(
                controllerUrl, { controllerUrl = it.trim() }, label = { Text("Controller URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                authMethodId, { authMethodId = it.trim() }, label = { Text("OIDC auth method ID (amoidc_…)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                targetId, { targetId = it.trim() }, label = { Text("Target ID (ttcp_…)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Host and port below are the server's real SSH address: they identify the host key you trust, " +
                    "on both routes.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(user, { user = it.trim() }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = { onConnect(SshTarget(host, portNum!!, user, if (viaBoundary) boundary!! else Route.Direct)) },
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }

        PublicKeyCard(publicKey)
    }
}

@Composable
fun ConnectingScreen(target: SshTarget, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        val via = if (target.route is Route.Boundary) " via Boundary" else ""
        Text("Connecting to ${target.user}@${target.alias}$via…", Modifier.padding(top = 16.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * Boundary OIDC: the login happens in the browser; the app polls for completion. The IdP URL is opened
 * once automatically and can be reopened. Only http(s) URLs are ever handed to the browser.
 */
@Composable
fun LoginScreen(target: SshTarget, authUrl: String, onCancel: () -> Unit, notice: String? = null) {
    val uriHandler = LocalUriHandler.current
    val openable = isOpenableAuthUrl(authUrl)
    var openError by remember { mutableStateOf<String?>(null) }
    fun open() {
        openError = runCatching { uriHandler.openUri(authUrl.trim()) }.exceptionOrNull()
            ?.let { "No browser could open the login page." }
    }
    // Survives Activity recreation (rotation while the user is in the browser) so the IdP page is not reopened.
    var autoOpened by rememberSaveable(authUrl) { mutableStateOf(false) }
    LaunchedEffect(authUrl) {
        if (openable && !autoOpened) {
            autoOpened = true
            open()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Sign in to Boundary in your browser", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        if (!notice.isNullOrBlank()) Text(notice, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        Text(
            "Waiting for the login to finish, then connecting to ${target.user}@${target.alias}…",
            Modifier.padding(top = 8.dp),
        )
        if (!openable) Text("The login address is not a web URL, so it was not opened.", color = MaterialTheme.colorScheme.error)
        openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (openable) OutlinedButton(onClick = ::open, Modifier.padding(top = 16.dp)) { Text("Open browser again") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** Trust-on-first-use prompt. A changed key is presented as a warning and defaults to refusing. */
@Composable
fun TofuScreen(challenge: HostKeyChallenge, route: Route?, onDecision: (trust: Boolean) -> Unit) {
    val changed = challenge.isChanged
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (changed) "Host key has CHANGED" else "Trust this host?",
            style = MaterialTheme.typography.headlineMedium,
            color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (changed) {
                "The key for ${challenge.alias} is different from the one you trusted before. " +
                    "This can mean the server was reinstalled — or that someone is intercepting the connection."
            } else {
                "You have not connected to ${challenge.alias} before. Verify the fingerprint with the server's administrator."
            },
        )
        if (route is Route.Boundary) Text("Connecting via Boundary", style = MaterialTheme.typography.labelMedium)
        Text("Key type", style = MaterialTheme.typography.labelMedium)
        Text(challenge.keyType, fontFamily = FontFamily.Monospace)
        if (changed) {
            Text("Previously trusted", style = MaterialTheme.typography.labelMedium)
            Text(challenge.previousFingerprint.orEmpty(), fontFamily = FontFamily.Monospace)
        }
        Text(if (changed) "New fingerprint" else "Fingerprint", style = MaterialTheme.typography.labelMedium)
        Text(challenge.fingerprint, fontFamily = FontFamily.Monospace)

        Button(onClick = { onDecision(false) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        OutlinedButton(
            onClick = { onDecision(true) },
            modifier = Modifier.fillMaxWidth(),
            colors = if (changed) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.outlinedButtonColors(),
        ) { Text(if (changed) "Replace trusted key and connect" else "Trust and connect") }
    }
}
