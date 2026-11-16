package run.scp.mobile.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The device's public key as one `authorized_keys` line, or null while it is still loading or if it is not
 * a single `ssh-ed25519 <base64>` line (so Copy/Share can never hand out something else).
 */
fun authorizedKeyLine(raw: String): String? {
    val line = raw.trim()
    if (line.contains('\n') || line.contains('\r')) return null
    val parts = line.split(' ')
    return if (parts.size >= 2 && parts[0] == "ssh-ed25519" && parts[1].isNotEmpty()) line else null
}

/** The public key with Copy and Share buttons. A public key is not a secret, so sharing it is fine. */
@Composable
fun PublicKeyCard(publicKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val line = authorizedKeyLine(publicKey)
    var copied by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Device public key", style = MaterialTheme.typography.titleSmall)
        Text("Add this line to the server's ~/.ssh/authorized_keys:", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text(line ?: publicKey.ifBlank { "…" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = line != null,
                onClick = {
                    if (line != null) {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("SSH public key", line))
                        copied = true
                    }
                },
            ) { Text(if (copied) "Copied" else "Copy") }
            OutlinedButton(enabled = line != null, onClick = { if (line != null) shareText(context, line) }) { Text("Share") }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share public key").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
