package run.scp.mobile.app.ssh

import run.scp.mobile.app.session.ShellEvents
import run.scp.mobile.app.session.ShellSession
import run.scp.mobile.app.tofu.HostKeyDecider
import run.scp.mobile.scpmobile.BoundarySession
import run.scp.mobile.scpmobile.Conn
import run.scp.mobile.scpmobile.HostKeyVerifier
import run.scp.mobile.scpmobile.OutputSink
import run.scp.mobile.scpmobile.Scpmobile

/** A Boundary transport [conn]; [close] ends the Boundary session (idempotent, may block). */
internal class BoundaryDial<C>(val conn: C, val close: () -> Unit)

/**
 * The native operations the connector needs, generic over the transport type [C] so the connector's
 * lifecycle rules can be tested on the JVM, where the gomobile classes cannot be instantiated.
 * All methods block.
 */
internal interface ScpBackend<C> {
    fun dialDirect(host: String, port: Int): C
    fun dialBoundary(controllerUrl: String, token: String, targetId: String): BoundaryDial<C>

    /** On failure the AAR has already closed [conn]. */
    fun openShell(
        conn: C,
        user: String,
        privateKey: ByteArray,
        alias: String,
        hostKeys: HostKeyDecider,
        events: ShellEvents,
        cols: Int,
        rows: Int,
    ): ShellSession
}

/** [ScpBackend] over scpmobile.aar. */
internal object NativeScpBackend : ScpBackend<Conn> {
    override fun dialDirect(host: String, port: Int): Conn = Scpmobile.dialDirect(host, port.toLong(), 0)

    override fun dialBoundary(controllerUrl: String, token: String, targetId: String): BoundaryDial<Conn> {
        val session: BoundarySession = Scpmobile.dialBoundary(controllerUrl, token, targetId)
        val conn = try {
            session.conn()
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
        return BoundaryDial(conn) { session.close() }
    }

    override fun openShell(
        conn: Conn,
        user: String,
        privateKey: ByteArray,
        alias: String,
        hostKeys: HostKeyDecider,
        events: ShellEvents,
        cols: Int,
        rows: Int,
    ): ShellSession {
        val verifier = object : HostKeyVerifier {
            override fun verify(alias: String, keyType: String, fingerprint: String, authorizedKey: ByteArray): Boolean =
                hostKeys.decide(alias, keyType, fingerprint)
        }
        val sink = object : OutputSink {
            override fun write(p0: ByteArray) = events.onOutput(p0)
            override fun closed(reason: String) = events.onClosed(reason)
        }
        val shell = Scpmobile.openShell(conn, user, privateKey, alias, verifier, sink, cols.toLong(), rows.toLong())
        return object : ShellSession {
            override fun write(bytes: ByteArray) = shell.write(bytes)
            override fun resize(cols: Int, rows: Int) = shell.resize(cols.toLong(), rows.toLong())
            override fun close() = shell.close()
        }
    }
}
