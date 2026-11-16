package run.scp.mobile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import run.scp.mobile.app.backup.ConnectTarget
import run.scp.mobile.app.servers.ImportState
import run.scp.mobile.app.servers.ServerRow
import run.scp.mobile.app.session.SshTarget

@Composable
fun ServerListScreen(
    rows: List<ServerRow>,
    loadError: String?,
    sessionError: String?,
    publicKey: String,
    onConnect: (SshTarget) -> Unit,
    onManual: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Servers", style = MaterialTheme.typography.headlineMedium)
        if (sessionError != null) Text(sessionError, color = MaterialTheme.colorScheme.error)
        if (loadError != null) Text("Saved servers could not be read: $loadError", color = MaterialTheme.colorScheme.error)
        if (rows.isEmpty()) Text("No servers yet. Import an SCP backup, or connect manually.")
        for (row in rows) ServerRowItem(row, onConnect)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onImport) { Text("Import backup") }
            OutlinedButton(onClick = onManual) { Text("Manual connect") }
        }
        PublicKeyCard(publicKey)
    }
}

@Composable
private fun ServerRowItem(row: ServerRow, onConnect: (SshTarget) -> Unit) {
    val ready = row.target as? ConnectTarget.Ready
    Column(
        Modifier.fillMaxWidth()
            .then(if (ready != null) Modifier.clickable { onConnect(ready.target) } else Modifier)
            .padding(vertical = 8.dp),
    ) {
        Text(row.title, style = MaterialTheme.typography.titleMedium)
        Text(row.subtitle, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        (row.target as? ConnectTarget.Unavailable)?.let {
            Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Backup import: pick a file, enter the passphrase, review what will be replaced, then apply. */
@Composable
fun ImportScreen(
    state: ImportState,
    onPick: () -> Unit,
    onDecrypt: (passphrase: String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    // Not rememberSaveable: the passphrase must never be written to saved instance state.
    var passphrase by remember { mutableStateOf("") }
    LaunchedEffect(state) {
        if (state is ImportState.Preview || state is ImportState.Done) passphrase = ""
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import backup", style = MaterialTheme.typography.headlineMedium)
        when (state) {
            is ImportState.Idle -> {
                Text("Choose an SCP settings backup (exported from the desktop app) and enter its passphrase.")
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text(state.fileName ?: "Choose file…") }
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("Backup passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { onDecrypt(passphrase) },
                    enabled = state.fileName != null && passphrase.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Decrypt") }
                TextButton(onClick = onCancel) { Text("Back") }
            }
            ImportState.Decrypting -> Working("Decrypting…", onCancel)
            ImportState.Applying -> Working("Saving…", null)
            is ImportState.Preview -> {
                val c = state.contents
                Text(state.fileName, fontFamily = FontFamily.Monospace)
                Text("${c.servers.size} server(s). Importing replaces your current server list.")
                c.boundary?.takeIf { it.controllerUrl.isNotBlank() }?.let {
                    Text("Boundary controller: ${it.controllerUrl}", style = MaterialTheme.typography.bodySmall)
                    if (it.authKind != "oidc") {
                        Text("Boundary password login is not supported; Boundary servers will be unavailable.", color = MaterialTheme.colorScheme.error)
                    }
                }
                val secrets = listOfNotNull(
                    "OIDC password".takeIf { c.secrets.oidcPassword != null },
                    "OIDC OTP secret".takeIf { c.secrets.oidcOtpSecret != null },
                )
                Text(
                    if (secrets.isEmpty()) "No Boundary secrets in this backup (saved ones will be cleared)."
                    else "Boundary secrets stored encrypted on this device: ${secrets.joinToString()}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (w in c.warnings) Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Replace servers") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is ImportState.Done -> {
                Text("Imported ${state.serverCount} server(s).")
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Working(label: String, onCancel: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(label, Modifier.padding(top = 16.dp))
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
