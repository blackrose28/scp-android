package run.scp.mobile.app.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import run.scp.mobile.app.secure.BoundaryTokenStore
import run.scp.mobile.app.tofu.TrustedHost

private class MemTokenStore : BoundaryTokenStore {
    val tokens = java.util.concurrent.ConcurrentHashMap<String, BoundaryToken>()
    @Volatile var failSaves = false
    override fun load(key: String) = tokens[key]
    override fun save(key: String, token: BoundaryToken) {
        if (failSaves) throw java.io.IOException("disk full")
        tokens[key] = token
    }
    override fun remove(key: String) { tokens.remove(key) }
}

/** Scripted login: each poll consumes the next step; the last step repeats. */
private class FakeLogin(
    override val authUrl: String = "https://idp.example/login?x=1",
    private val steps: List<() -> BoundaryToken?>,
) : BoundaryLogin {
    val polls = AtomicInteger()
    override suspend fun poll(): BoundaryToken? {
        val i = polls.getAndIncrement()
        return steps[minOf(i, steps.lastIndex)]()
    }
}

private class FakeAuth(
    private val login: () -> BoundaryLogin,
    private val startError: Throwable? = null,
) : BoundaryAuthenticator {
    val starts = AtomicInteger()
    @Volatile var lastStart: Pair<String, String>? = null
    override suspend fun start(controllerUrl: String, authMethodId: String): BoundaryLogin {
        starts.incrementAndGet()
        lastStart = controllerUrl to authMethodId
        startError?.let { throw it }
        return login()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BoundarySessionViewModelTest {
    private val route = Route.Boundary("https://boundary.example", "amoidc_abc", "ttcp_123")
    private val target = SshTarget("10.0.0.5", 22, "ops", route)
    private val store = MemStore().also { it.save(TrustedHost("10.0.0.5:22", "ssh-ed25519", "SHA256:fp")) }
    private val token = BoundaryToken("s3cret-token", expirationMs = 0)
    private var now = 1_000_000L

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        c: ShellConnector,
        auth: BoundaryAuthenticator?,
        pollMs: Long = 5,
        timeoutMs: Long = 5_000,
        tokenStore: BoundaryTokenStore? = null,
    ) = SessionViewModel(
        c, store, ioDispatcher = Dispatchers.IO, hostKeyTimeoutMs = 5_000,
        boundaryAuth = auth, loginPollIntervalMs = pollMs, loginTimeoutMs = timeoutMs, clock = { now },
        tokenStore = tokenStore,
    )

    private val key = "https://boundary.example\u0000amoidc_abc"

    private suspend fun <T> Flow<T>.await(p: (T) -> Boolean): T = withTimeout(5_000) { first(p) }

    private fun eventually(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            check(System.nanoTime() < deadline) { "condition not met within 5s" }
            Thread.sleep(5)
        }
    }

    @Test fun `alias is the real host and port for both routes`() {
        assertEquals("10.0.0.5:22", target.alias)
        assertEquals("10.0.0.5:22", SshTarget("10.0.0.5", 22, "ops").alias)
        assertEquals(Route.Direct, SshTarget("10.0.0.5", 22, "ops").route)
    }

    @Test fun `token never appears in its string form or in session state`() {
        assertFalse(token.toString().contains("s3cret"))
        assertFalse(SessionState.AwaitingLogin(target, "https://idp/").toString().contains("s3cret"))
        assertFalse(target.toString().contains("s3cret"))
    }

    @Test fun `login surfaces the auth url then connects with the token`() = runBlocking<Unit> {
        val c = FakeConnector()
        val login = FakeLogin(steps = listOf({ null }, { null }, { token }))
        val auth = FakeAuth({ login })
        val vm = vm(c, auth)
        vm.connect(target, 80, 24)
        val waiting = vm.state.await { it is SessionState.AwaitingLogin } as SessionState.AwaitingLogin
        assertEquals("https://idp.example/login?x=1", waiting.authUrl)
        assertEquals(target, waiting.target)
        vm.state.await { it is SessionState.Connected }
        assertEquals("https://boundary.example" to "amoidc_abc", auth.lastStart)
        assertEquals(token, c.openedToken)
        assertEquals(target, c.openedWith?.first)
        assertTrue(login.polls.get() >= 3)
    }

    @Test fun `direct route never touches the authenticator and passes no token`() = runBlocking<Unit> {
        val c = FakeConnector()
        val auth = FakeAuth({ error("must not be called") })
        val vm = vm(c, auth)
        vm.connect(SshTarget("10.0.0.5", 22, "ops"), 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(0, auth.starts.get())
        assertNull(c.openedToken)
    }

    @Test fun `boundary route without an authenticator fails clearly`() = runBlocking<Unit> {
        val vm = vm(FakeConnector(), auth = null)
        vm.connect(target, 80, 24)
        val f = vm.state.await { it is SessionState.Failed } as SessionState.Failed
        assertTrue(f.message.contains("Boundary"))
    }

    @Test fun `start error becomes Failed with its message`() = runBlocking<Unit> {
        val vm = vm(FakeConnector(), FakeAuth({ error("unused") }, startError = java.io.IOException("controller unreachable")))
        vm.connect(target, 80, 24)
        val f = vm.state.await { it is SessionState.Failed } as SessionState.Failed
        assertEquals("controller unreachable", f.message)
    }

    @Test fun `persistent poll errors fail the login`() = runBlocking<Unit> {
        val login = FakeLogin(steps = listOf({ throw java.io.IOException("boom") }))
        val c = FakeConnector()
        val vm = vm(c, FakeAuth({ login }))
        vm.connect(target, 80, 24)
        val f = vm.state.await { it is SessionState.Failed } as SessionState.Failed
        assertEquals("boom", f.message)
        assertEquals(0, c.openCount)
    }

    @Test fun `a transient poll error does not abort the login`() = runBlocking<Unit> {
        val login = FakeLogin(steps = listOf({ throw java.io.IOException("blip") }, { token }))
        val vm = vm(FakeConnector(), FakeAuth({ login }))
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
    }

    @Test fun `login times out`() = runBlocking<Unit> {
        val login = FakeLogin(steps = listOf({ null }))
        val c = FakeConnector()
        val vm = vm(c, FakeAuth({ login }), timeoutMs = 60)
        vm.connect(target, 80, 24)
        val f = vm.state.await { it is SessionState.Failed } as SessionState.Failed
        assertTrue(f.message.contains("timed out", ignoreCase = true))
        assertEquals(0, c.openCount)
    }

    @Test fun `cancel during login stops polling and never connects`() = runBlocking<Unit> {
        val login = FakeLogin(steps = listOf({ null }))
        val c = FakeConnector()
        val vm = vm(c, FakeAuth({ login }))
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.AwaitingLogin }
        vm.disconnect()
        assertEquals(SessionState.Idle, vm.state.value)
        Thread.sleep(50)
        val after = login.polls.get()
        Thread.sleep(80)
        assertEquals(after, login.polls.get())
        assertEquals(0, c.openCount)
    }

    @Test fun `a poll result that arrives after cancel is dropped`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val login = FakeLogin(steps = listOf({
            entered.countDown()
            release.await(5, TimeUnit.SECONDS) // blocking and not cancellable, like the native Poll
            token
        }))
        val c = FakeConnector()
        val vm = vm(c, FakeAuth({ login }))
        vm.connect(target, 80, 24)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vm.disconnect()
        release.countDown()
        Thread.sleep(100)
        assertEquals(SessionState.Idle, vm.state.value)
        assertEquals(0, c.openCount)
    }

    @Test fun `a second connect is ignored while waiting for login`() = runBlocking<Unit> {
        val login = FakeLogin(steps = listOf({ null }))
        val auth = FakeAuth({ login })
        val vm = vm(FakeConnector(), auth)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.AwaitingLogin }
        vm.connect(target, 80, 24)
        assertEquals(1, auth.starts.get())
    }

    @Test fun `input while waiting for login is dropped`() = runBlocking<Unit> {
        val c = FakeConnector()
        val vm = vm(c, FakeAuth({ FakeLogin(steps = listOf({ null })) }))
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.AwaitingLogin }
        vm.send("x")
        vm.disconnect()
        Thread.sleep(30)
        assertTrue(c.session.written.isEmpty())
    }

    @Test fun `a valid token is reused by the next connect without a new login`() = runBlocking<Unit> {
        val c = FakeConnector()
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("t1", now + 3_600_000) })) })
        val vm = vm(c, auth)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        vm.disconnect()
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(1, auth.starts.get())
        assertEquals("t1", c.openedToken?.value)
    }

    @Test fun `an expired or nearly expired token triggers a new login`() = runBlocking<Unit> {
        val c = FakeConnector()
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("t", now + 30_000) })) }) // inside the safety margin
        val vm = vm(c, auth)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        vm.disconnect()
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(2, auth.starts.get())
    }

    @Test fun `the cache is keyed by controller and auth method`() = runBlocking<Unit> {
        val c = FakeConnector()
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("t", now + 3_600_000) })) })
        val vm = vm(c, auth)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        vm.disconnect()
        vm.connect(target.copy(route = route.copy(controllerUrl = "https://other.example")), 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(2, auth.starts.get())
    }

    @Test fun `a failed boundary connect drops the cached token`() = runBlocking<Unit> {
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("t", now + 3_600_000) })) })
        val c = FakeConnector(failWith = java.io.IOException("authorize session: 401"))
        val vm = vm(c, auth)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Failed }
        vm.disconnect()
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Failed }
        assertEquals(2, auth.starts.get())
    }

    @Test fun `a fresh login is persisted`() = runBlocking<Unit> {
        val ts = MemTokenStore()
        val saved = BoundaryToken("t1", now + 3_600_000)
        val vm = vm(FakeConnector(), FakeAuth({ FakeLogin(steps = listOf({ saved })) }), tokenStore = ts)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(saved, ts.tokens[key])
    }

    @Test fun `a persisted token is used after a restart without a login`() = runBlocking<Unit> {
        val ts = MemTokenStore().also { it.tokens[key] = BoundaryToken("persisted", now + 3_600_000) }
        val c = FakeConnector()
        val auth = FakeAuth({ error("must not log in") })
        val vm = vm(c, auth, tokenStore = ts) // a new ViewModel, as after a process restart
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(0, auth.starts.get())
        assertEquals("persisted", c.openedToken?.value)
    }

    @Test fun `an expired persisted token is discarded and a new login runs`() = runBlocking<Unit> {
        val ts = MemTokenStore().also { it.tokens[key] = BoundaryToken("old", now + 30_000) } // inside the margin
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("new", now + 3_600_000) })) })
        val c = FakeConnector()
        val vm = vm(c, auth, tokenStore = ts)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals(1, auth.starts.get())
        assertEquals("new", c.openedToken?.value)
        assertEquals("new", ts.tokens[key]?.value)
    }

    @Test fun `a persisted token for another controller is not used`() = runBlocking<Unit> {
        val ts = MemTokenStore().also { it.tokens["https://other.example\u0000amoidc_abc"] = BoundaryToken("other", now + 3_600_000) }
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("mine", now + 3_600_000) })) })
        val c = FakeConnector()
        val vm = vm(c, auth, tokenStore = ts)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals("mine", c.openedToken?.value)
        assertEquals("other", ts.tokens["https://other.example\u0000amoidc_abc"]?.value)
    }

    @Test fun `a failed boundary connect also forgets the persisted token`() = runBlocking<Unit> {
        val ts = MemTokenStore().also { it.tokens[key] = BoundaryToken("stale", now + 3_600_000) }
        val vm = vm(FakeConnector(failWith = java.io.IOException("authorize session: 401")), FakeAuth({ error("unused") }), tokenStore = ts)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Failed }
        assertNull(ts.tokens[key])
    }

    @Test fun `a token store that cannot save does not block the connection`() = runBlocking<Unit> {
        val ts = MemTokenStore().also { it.failSaves = true }
        val c = FakeConnector()
        val auth = FakeAuth({ FakeLogin(steps = listOf({ BoundaryToken("t1", now + 3_600_000) })) })
        val vm = vm(c, auth, tokenStore = ts)
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        assertEquals("t1", c.openedToken?.value)
        vm.disconnect()
        vm.connect(target, 80, 24) // still reused from memory
        vm.state.await { it is SessionState.Connected }
        assertEquals(1, auth.starts.get())
    }

    @Test fun `a login that was cancelled is not persisted`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val login = FakeLogin(steps = listOf({
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            BoundaryToken("late", now + 3_600_000)
        }))
        val ts = MemTokenStore()
        val vm = vm(FakeConnector(), FakeAuth({ login }), tokenStore = ts)
        vm.connect(target, 80, 24)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vm.disconnect()
        release.countDown()
        Thread.sleep(100)
        assertTrue(ts.tokens.isEmpty())
    }

    @Test fun `release closes a live session even without the worker queue`() = runBlocking<Unit> {
        val c = FakeConnector()
        val vm = vm(c, FakeAuth({ FakeLogin(steps = listOf({ token })) }))
        vm.connect(target, 80, 24)
        vm.state.await { it is SessionState.Connected }
        vm.release()
        eventually { c.session.closed >= 1 }
        Thread.sleep(50)
        assertEquals(1, c.session.closed)
    }
}
