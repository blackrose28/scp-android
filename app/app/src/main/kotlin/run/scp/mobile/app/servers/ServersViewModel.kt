package run.scp.mobile.app.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import run.scp.mobile.app.backup.BackupContents
import run.scp.mobile.app.backup.BackupDecryptor
import run.scp.mobile.app.backup.BackupParser
import run.scp.mobile.app.secure.SecretStore

sealed interface ImportState {
    /** Waiting for a file and passphrase. [error] describes the last failure. */
    data class Idle(val fileName: String?, val error: String?) : ImportState
    data object Decrypting : ImportState

    /** Decrypted and parsed, nothing persisted yet. The user confirms with [ServersViewModel.apply]. */
    data class Preview(val fileName: String, val contents: BackupContents) : ImportState
    data object Applying : ImportState
    data class Done(val serverCount: Int) : ImportState
}

/**
 * The imported server list plus the backup-import flow (pick, decrypt, preview, apply).
 *
 * Importing replaces the whole list (the Git-sync merge rule comes later). Applying saves the secrets first
 * and restores the previous ones if writing the list fails, so a failed import leaves the old state.
 * The file and the decrypted contents are kept in memory only until applied or cancelled.
 */
class ServersViewModel(
    private val decryptor: BackupDecryptor,
    private val repo: ServerRepository,
    private val secrets: SecretStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val lock = Any()
    private var attempt = 0L
    private var file: Pair<String, ByteArray>? = null
    private var pending: BackupContents? = null

    private val _servers = MutableStateFlow(StoredServers.EMPTY)
    val servers: StateFlow<StoredServers> = _servers.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle(null, null))
    val state: StateFlow<ImportState> = _state.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            try {
                _servers.value = repo.load()
            } catch (e: Exception) {
                _loadError.value = e.message ?: "Could not read the saved servers"
            }
        }
    }

    fun chooseFile(name: String, bytes: ByteArray) = synchronized(lock) {
        if (_state.value is ImportState.Decrypting || _state.value is ImportState.Applying) return
        attempt++
        file = name to bytes
        pending = null
        _state.value = ImportState.Idle(name, null)
    }

    fun fileReadFailed(message: String) = synchronized(lock) {
        if (_state.value is ImportState.Decrypting || _state.value is ImportState.Applying) return
        attempt++
        file = null
        pending = null
        _state.value = ImportState.Idle(null, message)
    }

    /** The passphrase is used as typed (no trimming) and not retained. */
    fun decrypt(passphrase: String) {
        val id: Long
        val (name, bytes) = synchronized(lock) {
            if (_state.value !is ImportState.Idle) return
            val f = file ?: run {
                _state.value = ImportState.Idle(null, "Choose a backup file first")
                return
            }
            if (passphrase.isEmpty()) {
                _state.value = ImportState.Idle(f.first, "Enter the backup passphrase")
                return
            }
            id = ++attempt
            _state.value = ImportState.Decrypting
            f
        }
        viewModelScope.launch(ioDispatcher) {
            val result = try {
                val plain = decryptor.decrypt(bytes, passphrase)
                try {
                    Result.success(BackupParser.parse(plain))
                } finally {
                    plain.fill(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // The native decrypt cannot be interrupted, so a result can arrive after the user cancelled.
            synchronized(lock) {
                if (attempt != id || _state.value !is ImportState.Decrypting) return@launch
                result.fold(
                    onSuccess = {
                        pending = it
                        file = null
                        _state.value = ImportState.Preview(name, it)
                    },
                    onFailure = { _state.value = ImportState.Idle(name, it.message ?: "Could not read the backup") },
                )
            }
        }
    }

    fun apply() {
        val contents = synchronized(lock) {
            if (_state.value !is ImportState.Preview) return
            val c = pending ?: return
            _state.value = ImportState.Applying
            c
        }
        viewModelScope.launch(ioDispatcher) {
            val stored = StoredServers(contents.servers, contents.boundary)
            val error = try {
                val previous = secrets.load()
                secrets.save(contents.secrets)
                try {
                    repo.replace(stored)
                } catch (e: Exception) {
                    runCatching { secrets.save(previous) }
                    throw e
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Could not save the import"
            }
            synchronized(lock) {
                pending = null
                if (error == null) {
                    _servers.value = stored
                    _state.value = ImportState.Done(stored.servers.size)
                } else {
                    _state.value = ImportState.Idle(null, error)
                }
            }
        }
    }

    /** Abandons the import: drops the file, the decrypted contents and any in-flight decrypt result. */
    fun cancel() = synchronized(lock) {
        if (_state.value is ImportState.Applying) return
        attempt++
        file = null
        pending = null
        _state.value = ImportState.Idle(null, null)
    }

    /** Leaves the Done screen. */
    fun finish() = cancel()
}
