package run.scp.mobile.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import run.scp.mobile.app.secure.BoundaryTokenStore
import run.scp.mobile.app.terminal.ScreenSnapshot
import run.scp.mobile.app.terminal.TerminalEmulator
import run.scp.mobile.app.tofu.HostKeyChallenge
import run.scp.mobile.app.tofu.KnownHostsStore
import run.scp.mobile.app.tofu.TofuHostKeyDecider

sealed interface SessionState {
    data object Idle : SessionState
    data class Connecting(val target: SshTarget) : SessionState

    /** Boundary route: the user must finish the OIDC login in a browser at [authUrl]; the app is polling. */
    data class AwaitingLogin(val target: SshTarget, val authUrl: String) : SessionState
    data class Connected(val target: SshTarget) : SessionState
    data class Ended(val target: SshTarget, val reason: String?) : SessionState
    data class Failed(val target: SshTarget, val message: String) : SessionState
}

/** What the terminal looks like right now; [revision] changes with every published picture. */
data class TerminalSnapshot(val screen: ScreenSnapshot, val revision: Long)

/**
 * Owns one SSH shell at a time: connection state, the Boundary OIDC login, the TOFU host-key
 * prompt, and the rendered terminal. Shell writes/resizes/close go through an ordered
 * per-connection queue on [ioDispatcher] so a stalled network never blocks the UI thread.
 *
 * Boundary tokens are cached in memory and, when a [tokenStore] is given, in encrypted storage so they
 * survive a restart. They are keyed by controller + auth method and handed to the connector per connect;
 * they are never part of [SessionState]. A token that is expired (or about to be) is not reused, and any
 * failed Boundary connect discards the cached token so the retry logs in again (a stale token cannot be
 * told apart from other failures). A store that cannot save or remove never blocks a connection: the token
 * then lives in memory only. The store is only touched from the worker coroutine on [ioDispatcher].
 */
class SessionViewModel(
    private val connector: ShellConnector,
    store: KnownHostsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    hostKeyTimeoutMs: Long = 120_000,
    private val boundaryAuth: BoundaryAuthenticator? = null,
    private val loginPollIntervalMs: Long = 2_000,
    private val loginTimeoutMs: Long = 300_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenStore: BoundaryTokenStore? = null,
) : ViewModel() {

    private class Attempt(val id: Long, val target: SshTarget) {
        val ops = Channel<(ShellSession) -> Unit>(Channel.UNLIMITED)
        var job: Job? = null
        var session: ShellSession? = null
        var finished = false // Ended/Failed/disconnected: ignore later events
    }

    private val lock = Any()
    private var attempt: Attempt? = null
    private var nextId = 0L
    private val tokens = mutableMapOf<String, BoundaryToken>() // guarded by lock

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _pendingHostKey = MutableStateFlow<HostKeyChallenge?>(null)
    val pendingHostKey: StateFlow<HostKeyChallenge?> = _pendingHostKey.asStateFlow()
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    private var emulator = TerminalEmulator(DEFAULT_COLS, DEFAULT_ROWS) // guarded by lock
    private var revision = 0L
    private val _terminal = MutableStateFlow(TerminalSnapshot(emulator.snapshot(), 0))
    val terminal: StateFlow<TerminalSnapshot> = _terminal.asStateFlow()
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private val decider = TofuHostKeyDecider(store, ::promptHostKey, hostKeyTimeoutMs)

    init {
        // Coalesce bursts of output into at most one snapshot per frame-ish interval.
        viewModelScope.launch(ioDispatcher) {
            for (ignored in dirty) {
                publishTerminal()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    fun connect(target: SshTarget, cols: Int, rows: Int) {
        val a: Attempt
        synchronized(lock) {
            val s = _state.value
            if (s is SessionState.Connecting || s is SessionState.AwaitingLogin || s is SessionState.Connected) return
            a = Attempt(++nextId, target)
            attempt = a
            // Programs query the terminal (cursor position, device attributes); answer on this attempt's shell.
            emulator = TerminalEmulator(cols.coerceAtLeast(1), rows.coerceAtLeast(1)) { reply ->
                a.ops.trySend { it.write(reply) }
            }
            _state.value = SessionState.Connecting(target)
        }
        publishTerminal()

        val events = object : ShellEvents {
            override fun onOutput(bytes: ByteArray) {
                synchronized(lock) {
                    if (attempt !== a || a.finished) return
                    emulator.append(bytes)
                }
                dirty.trySend(Unit)
            }

            override fun onClosed(reason: String) {
                synchronized(lock) {
                    if (attempt !== a || a.finished) return
                    a.finished = true
                    _state.value = SessionState.Ended(target, reason.ifBlank { null })
                    a.ops.trySend { it.close() }
                    a.ops.close()
                }
                publishTerminal()
            }
        }

        val job = viewModelScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            val route = target.route
            val token = if (route is Route.Boundary) obtainToken(a, route) ?: return@launch else null
            val session = try {
                connector.open(target, cols, rows, decider, events, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (route is Route.Boundary) forgetToken(tokenKey(route))
                fail(a, e)
                return@launch
            }
            val keep = synchronized(lock) {
                if (attempt === a && !a.finished) {
                    a.session = session
                    _state.value = SessionState.Connected(target)
                    true
                } else {
                    false // disconnected or closed while the handshake was in flight
                }
            }
            if (!keep) {
                runCatching { session.close() }
                a.ops.close()
                return@launch
            }
            for (op in a.ops) runCatching { op(session) }
        }
        synchronized(lock) {
            a.job = job
            if (a.finished) job.cancel()
        }
        job.start()
    }

    private fun fail(a: Attempt, e: Exception) = fail(a, e.message ?: e::class.java.simpleName)

    private fun fail(a: Attempt, message: String) {
        synchronized(lock) {
            if (attempt === a && !a.finished) {
                a.finished = true
                a.ops.close()
                _state.value = SessionState.Failed(a.target, message)
            }
        }
    }

    private fun tokenKey(route: Route.Boundary) = "${route.controllerUrl}\u0000${route.authMethodId}"

    private fun usable(t: BoundaryToken) = t.expirationMs == 0L || t.expirationMs - clock() > TOKEN_MARGIN_MS

    private fun forgetToken(key: String) {
        synchronized(lock) { tokens.remove(key) }
        runCatching { tokenStore?.remove(key) }
    }

    /** The memory cache first, then the store (an unusable stored token is deleted); null when neither has one. */
    private fun cachedToken(key: String): BoundaryToken? {
        synchronized(lock) { tokens[key] }?.let { if (usable(it)) return it }
        val stored = tokenStore?.let { runCatching { it.load(key) }.getOrNull() } ?: return null
        if (!usable(stored)) {
            forgetToken(key)
            return null
        }
        synchronized(lock) { tokens[key] = stored }
        return stored
    }

    /**
     * A usable cached token, or a fresh one from an OIDC login the user completes in a browser.
     * Returns null after moving the attempt to Failed, or when the attempt was cancelled meanwhile.
     */
    private suspend fun obtainToken(a: Attempt, route: Route.Boundary): BoundaryToken? {
        val key = tokenKey(route)
        cachedToken(key)?.let { return it }
        val auth = boundaryAuth ?: run { fail(a, "Boundary login is not available"); return null }

        val token = try {
            val login = auth.start(route.controllerUrl, route.authMethodId)
            val waiting = synchronized(lock) {
                if (attempt === a && !a.finished) {
                    _state.value = SessionState.AwaitingLogin(a.target, login.authUrl)
                    true
                } else {
                    false
                }
            }
            if (!waiting) return null
            pollUntilDone(login) ?: run { fail(a, "Boundary login timed out"); return null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(a, e)
            return null
        }

        // The native Poll cannot be interrupted, so a result can arrive after the user cancelled.
        val accepted = synchronized(lock) {
            if (attempt === a && !a.finished) {
                tokens[key] = token
                _state.value = SessionState.Connecting(a.target)
                true
            } else {
                false
            }
        }
        if (!accepted) return null
        runCatching { tokenStore?.save(key, token) }
        return token
    }

    /** Polls until the login completes; null on timeout. A few consecutive poll errors are tolerated. */
    private suspend fun pollUntilDone(login: BoundaryLogin): BoundaryToken? = withTimeoutOrNull(loginTimeoutMs) {
        var token: BoundaryToken? = null
        var errors = 0
        while (token == null) {
            try {
                token = login.poll()
                errors = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++errors >= MAX_POLL_ERRORS) throw e
            }
            if (token == null) delay(loginPollIntervalMs)
        }
        token
    }

    fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    fun send(bytes: ByteArray) = enqueue { it.write(bytes) }

    fun resize(cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        val accepted = synchronized(lock) {
            val a = attempt
            if (a == null || a.finished || _state.value !is SessionState.Connected) {
                false
            } else {
                emulator.resize(cols, rows)
                a.ops.trySend { it.resize(cols, rows) }
                true
            }
        }
        if (accepted) dirty.trySend(Unit)
    }

    private fun enqueue(op: (ShellSession) -> Unit) {
        synchronized(lock) {
            val a = attempt ?: return
            if (a.finished || _state.value !is SessionState.Connected) return
            a.ops.trySend(op)
        }
    }

    /** Aborts any connect or login in progress, closes the shell, and returns to Idle. */
    fun disconnect() = teardown(detachedClose = false)

    /**
     * Like [disconnect] for when the ViewModel is going away: [viewModelScope] is already cancelled,
     * so the worker queue can no longer be relied on to close the session. Closing may block
     * (an authorized Boundary session is ended server-side), so it runs on its own thread.
     */
    internal fun release() = teardown(detachedClose = true)

    private fun teardown(detachedClose: Boolean) {
        synchronized(lock) {
            attempt?.let { a ->
                if (!a.finished) {
                    a.finished = true
                    val session = a.session
                    when {
                        session == null -> a.job?.cancel() // still logging in or connecting
                        detachedClose -> Thread { runCatching { session.close() } }
                            .apply { name = "scp-session-close"; isDaemon = true }.start()
                        else -> a.ops.trySend { it.close() }
                    }
                }
                a.ops.close()
            }
            attempt = null
            _state.value = SessionState.Idle
        }
        pendingDecision?.complete(false)
        _pendingHostKey.value = null // clear now; the prompting coroutine unwinds asynchronously
    }

    /** The user's answer to [pendingHostKey]. */
    fun resolveHostKey(trust: Boolean) {
        pendingDecision?.complete(trust)
    }

    private suspend fun promptHostKey(challenge: HostKeyChallenge): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _pendingHostKey.value = challenge
        try {
            return decision.await()
        } finally {
            // Only clear our own prompt; a newer attempt may already have posted another.
            if (pendingDecision === decision) {
                _pendingHostKey.value = null
                pendingDecision = null
            }
        }
    }

    private fun publishTerminal() {
        val screen = synchronized(lock) { emulator.snapshot() }
        _terminal.value = TerminalSnapshot(screen, ++revision)
    }

    override fun onCleared() {
        release()
    }

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val PUBLISH_INTERVAL_MS = 16L
        const val MAX_POLL_ERRORS = 3
        const val TOKEN_MARGIN_MS = 60_000L
    }
}
