package run.scp.mobile.app.ssh

import java.util.concurrent.atomic.AtomicBoolean
import run.scp.mobile.app.session.BoundaryToken
import run.scp.mobile.app.session.Route
import run.scp.mobile.app.session.ShellConnector
import run.scp.mobile.app.session.ShellEvents
import run.scp.mobile.app.session.ShellSession
import run.scp.mobile.app.session.SshTarget
import run.scp.mobile.app.tofu.HostKeyDecider

/** Backed by scpmobile.aar: DialDirect or DialBoundary for transport, OpenShell for SSH + PTY. */
class OpenShellConnector(identity: DeviceIdentity) : ShellConnector by BackendShellConnector(identity, NativeScpBackend)

internal class BackendShellConnector<C>(
    private val identity: DeviceIdentity,
    private val backend: ScpBackend<C>,
) : ShellConnector {

    override suspend fun open(
        target: SshTarget,
        cols: Int,
        rows: Int,
        hostKeys: HostKeyDecider,
        events: ShellEvents,
        boundaryToken: BoundaryToken?,
    ): ShellSession {
        var boundaryClose: (() -> Unit)? = null
        val conn: C = when (val route = target.route) {
            Route.Direct -> backend.dialDirect(target.host, target.port)
            is Route.Boundary -> {
                requireNotNull(boundaryToken) { "a Boundary token is required for a Boundary route" }
                val dial = backend.dialBoundary(route.controllerUrl, boundaryToken.value, route.targetId)
                boundaryClose = dial.close
                dial.conn
            }
        }
        val shell = try {
            // The host-key alias is the server's real host:port on both routes, never the controller address.
            backend.openShell(conn, target.user, identity.privateKeyPem(), target.alias, hostKeys, events, cols, rows)
        } catch (e: Throwable) {
            boundaryClose?.let { runCatching(it) } // the AAR already closed the transport; end the Boundary session too
            throw e
        }
        return ClosingShell(shell, boundaryClose)
    }

    /** Closes the shell, then the Boundary session (if any), once. */
    private class ClosingShell(private val shell: ShellSession, private val afterClose: (() -> Unit)?) : ShellSession {
        private val closed = AtomicBoolean()
        override fun write(bytes: ByteArray) = shell.write(bytes)
        override fun resize(cols: Int, rows: Int) = shell.resize(cols, rows)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                shell.close()
            } finally {
                afterClose?.invoke()
            }
        }
    }
}
