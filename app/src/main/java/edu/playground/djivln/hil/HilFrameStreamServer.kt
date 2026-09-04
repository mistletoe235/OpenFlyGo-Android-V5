package edu.playground.djivln.hil

import android.content.Context
import android.os.SystemClock
import android.util.Log
import edu.playground.djivln.R
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android-owned TCP endpoint. UE connects and pushes complete JPEG/PNG frames. */
internal class HilFrameStreamServer(
    private val port: Int,
    private val acceptedPeerHost: () -> String?,
    private val listener: Listener,
    context: Context? = null,
) : AutoCloseable {
    private data class PendingFrame(
        val frame: HilFrameProtocol.Frame,
        val connectionGeneration: Long,
    )

    private val appContext = context?.applicationContext
    interface Listener {
        fun onFrame(frame: HilFrameProtocol.Frame, connectionGeneration: Long)
        fun onListeningChanged(listening: Boolean, message: String)
        fun onFrameConnectionChanged(
            connected: Boolean,
            peerHost: String?,
            connectionGeneration: Long,
            message: String,
        )
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openfly-hil-frame-server").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    private var connectionGeneration = 0L
    private val frameDispatcher = HilLatestFrameDispatcher<PendingFrame>("openfly-hil-frame-server-dispatch") {
        listener.onFrame(it.frame, it.connectionGeneration)
    }
    private val readTelemetry = HilFrameReadTelemetry("server")

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 4 * 1024 * 1024
            }
            listener.onListeningChanged(
                true,
                text(R.string.hil_tcp_listening, "TCP 0.0.0.0:$port is listening", port),
            )
            executor.execute(::acceptLoop)
            true
        } catch (error: Throwable) {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            frameDispatcher.close()
            executor.shutdownNow()
            listener.onListeningChanged(false, error.message ?: error.javaClass.simpleName)
            false
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val accepted = serverSocket?.accept() ?: break
                val peerHost = accepted.inetAddress?.hostAddress
                val expectedHost = acceptedPeerHost()
                if (expectedHost.isNullOrBlank() || peerHost != expectedHost) {
                    listener.onFrameConnectionChanged(
                        false,
                        peerHost,
                        connectionGeneration,
                        text(
                            R.string.hil_tcp_peer_rejected,
                            "Rejected TCP ${peerHost ?: "unknown"}; complete the UDP session handshake first",
                            peerHost ?: "unknown",
                        ),
                    )
                    accepted.close()
                    continue
                }
                runCatching { clientSocket?.close() }
                clientSocket = accepted.apply {
                    tcpNoDelay = true
                    keepAlive = true
                    receiveBufferSize = 4 * 1024 * 1024
                }
                connectionGeneration += 1L
                val generation = connectionGeneration
                listener.onFrameConnectionChanged(true, peerHost, generation, "TCP ${peerHost ?: "UE"}:${accepted.port}")
                DataInputStream(BufferedInputStream(accepted.getInputStream(), 512 * 1024)).use { input ->
                    while (running.get() && !accepted.isClosed) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val frame = HilFrameProtocol.read(input, SystemClock::elapsedRealtimeNanos)
                        frameDispatcher.offer(PendingFrame(frame, generation))
                        readTelemetry.record(
                            frame.encoded.size,
                            SystemClock.elapsedRealtimeNanos() - started,
                            frameDispatcher.snapshot(),
                        )
                    }
                }
            } catch (error: Throwable) {
                if (running.get() && error !is SocketException) {
                    Log.w(TAG, "frame stream server read failed on $port", error)
                    listener.onFrameConnectionChanged(
                        false, null, connectionGeneration, error.message ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                runCatching { clientSocket?.close() }
                clientSocket = null
                if (running.get()) {
                    listener.onFrameConnectionChanged(
                        false,
                        null,
                        connectionGeneration,
                        text(R.string.hil_tcp_waiting_for_ue, "TCP $port is waiting for UE", port),
                    )
                }
            }
        }
    }

    /** Drops the authenticated image peer while leaving the listening socket available. */
    fun disconnectClient() {
        runCatching { clientSocket?.close() }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
        listener.onListeningChanged(false, text(R.string.hil_tcp_closed, "TCP $port is closed", port))
        frameDispatcher.close()
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "OpenFlyHilFrame"
    }

    private fun text(resourceId: Int, fallback: String, vararg arguments: Any): String =
        appContext?.getString(resourceId, *arguments) ?: fallback
}
