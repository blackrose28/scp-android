package run.scp.mobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.scp.mobile.app.backup.NativeBackupDecryptor
import run.scp.mobile.app.backup.readBounded
import run.scp.mobile.app.secure.KeystoreSealer
import run.scp.mobile.app.secure.SealedFileSecretStore
import run.scp.mobile.app.servers.FileServerRepository
import run.scp.mobile.app.servers.ServersViewModel
import run.scp.mobile.app.servers.serverRows
import run.scp.mobile.app.session.SessionState
import run.scp.mobile.app.session.SessionViewModel
import run.scp.mobile.app.ssh.KeystoreDeviceIdentity
import run.scp.mobile.app.ssh.NativeBoundaryAuthenticator
import run.scp.mobile.app.ssh.OpenShellConnector
import run.scp.mobile.app.tofu.FileKnownHostsStore
import run.scp.mobile.app.ui.ConnectScreen
import run.scp.mobile.app.ui.ConnectingScreen
import run.scp.mobile.app.ui.ImportScreen
import run.scp.mobile.app.ui.ServerListScreen
import run.scp.mobile.app.ui.LoginScreen
import run.scp.mobile.app.ui.TerminalScreen
import run.scp.mobile.app.ui.TofuScreen

private enum class Screen { Servers, Manual, Import }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val identity = KeystoreDeviceIdentity(applicationContext)
        val knownHosts = FileKnownHostsStore(File(filesDir, "known_hosts"))
        val sessionFactory = viewModelFactory {
            initializer {
                SessionViewModel(OpenShellConnector(identity), knownHosts, boundaryAuth = NativeBoundaryAuthenticator())
            }
        }
        val serversFactory = viewModelFactory {
            initializer {
                ServersViewModel(
                    NativeBackupDecryptor(),
                    FileServerRepository(File(filesDir, "servers.json")),
                    SealedFileSecretStore(File(filesDir, "boundary_secrets.enc"), KeystoreSealer("scp-mobile-secrets-wrap")),
                )
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val session: SessionViewModel = viewModel(factory = sessionFactory)
                val servers: ServersViewModel = viewModel(factory = serversFactory)
                var publicKey by remember { mutableStateOf("") }
                // Key generation/decryption touches the Keystore and native code: keep it off the UI thread.
                LaunchedEffect(Unit) { publicKey = withContext(Dispatchers.IO) { identity.publicAuthorizedKey() } }
                Box(Modifier.fillMaxSize()) { AppRoot(session, servers, publicKey) }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: SessionViewModel, servers: ServersViewModel, publicKey: String) {
    val state by vm.state.collectAsStateWithLifecycle()
    val challenge by vm.pendingHostKey.collectAsStateWithLifecycle()
    val terminal by vm.terminal.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Servers) }

    val pending = challenge
    when {
        pending != null -> TofuScreen(
            pending,
            route = (state as? SessionState.Connecting)?.target?.route,
            onDecision = vm::resolveHostKey,
        )
        else -> when (val s = state) {
            SessionState.Idle, is SessionState.Failed -> {
                val error = (s as? SessionState.Failed)?.message
                fun go(to: Screen) {
                    if (s is SessionState.Failed) vm.disconnect() // clear the stale error when moving on
                    screen = to
                }
                fun connect(target: run.scp.mobile.app.session.SshTarget) {
                    vm.disconnect()
                    vm.connect(target, 80, 24)
                }
                when (screen) {
                    Screen.Servers -> {
                        val stored by servers.servers.collectAsStateWithLifecycle()
                        val loadError by servers.loadError.collectAsStateWithLifecycle()
                        ServerListScreen(
                            rows = remember(stored) { serverRows(stored) },
                            loadError = loadError,
                            sessionError = error,
                            publicKey = publicKey,
                            onConnect = ::connect,
                            onManual = { go(Screen.Manual) },
                            onImport = { go(Screen.Import) },
                        )
                    }
                    Screen.Manual -> {
                        BackHandler { go(Screen.Servers) }
                        ConnectScreen(publicKey, error, onConnect = ::connect, onBack = { go(Screen.Servers) })
                    }
                    Screen.Import -> ImportRoot(servers, onExit = { screen = Screen.Servers })
                }
            }
            is SessionState.Connecting -> ConnectingScreen(s.target, onCancel = vm::disconnect)
            is SessionState.AwaitingLogin -> LoginScreen(s.target, s.authUrl, onCancel = vm::disconnect)
            is SessionState.Connected, is SessionState.Ended -> TerminalScreen(
                state = s,
                terminal = terminal,
                onSend = vm::send,
                onResize = vm::resize,
                onClose = vm::disconnect,
            )
        }
    }
}

@Composable
private fun ImportRoot(servers: ServersViewModel, onExit: () -> Unit) {
    val state by servers.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "backup"
                val bytes = resolver.openInputStream(uri)?.use { readBounded(it) } ?: error("Could not open the file")
                servers.chooseFile(name, bytes)
            } catch (e: Exception) {
                servers.fileReadFailed(e.message ?: "Could not read the file")
            }
        }
    }
    BackHandler {
        servers.cancel()
        onExit()
    }
    ImportScreen(
        state = state,
        // `*/*`: backups are opaque binary files with no registered MIME type.
        onPick = { picker.launch(arrayOf("*/*")) },
        onDecrypt = servers::decrypt,
        onApply = servers::apply,
        onCancel = {
            servers.cancel()
            onExit()
        },
        onDone = {
            servers.finish()
            onExit()
        },
    )
}
