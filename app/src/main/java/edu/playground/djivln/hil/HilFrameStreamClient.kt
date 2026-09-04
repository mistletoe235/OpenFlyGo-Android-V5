package edu.playground.djivln.hil

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class HilFrameStreamClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) : AutoCloseable {
    private data class PendingFrame(
        val frame: HilFrameProtocol.Frame,
        val connectionGeneration: Long,
    )

    interface Listener {
        fun onFrame(frame: HilFrameProtocol.Frame, connectionGeneration: Long)
        fun onFrameConnectionChanged(connected: Boolean, connectionGeneration: Long, message: String)
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openfly-hil-frame").apply { isDaemon = true }
    }
    @Volatile private var socket: Socket? = null
    private var connectionGeneration = 0L
    private val frameDispatcher = HilLatestFrameDispatcher<PendingFrame>("openfly-hil-frame-dispatch") {
        listener.onFrame(it.frame, it.connectionGeneration)
    }
    private val readTelemetry = HilFrameReadTelemetry("client")

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute(::runLoop)
    }

    private fun runLoop() {
        var reconnectDelayMillis = MIN_RECONNECT_DELAY_MS
        var lastWarningMillis = Long.MIN_VALUE
        while (running.get()) {
            try {
                val active = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    receiveBufferSize = 4 * 1024 * 1024
                    connect(InetSocketAddress(host, port), 1_500)
                }
                socket = active
                reconnectDelayMillis = MIN_RECONNECT_DELAY_MS
                connectionGeneration += 1L
                val generation = connectionGeneration
                listener.onFrameConnectionChanged(true, generation, "TCP $host:$port")
                DataInputStream(BufferedInputStream(active.getInputStream(), 256 * 1024)).use { input ->
                    while (running.get()) {
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
                if (running.get()) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastWarningMillis == Long.MIN_VALUE || now - lastWarningMillis >= WARNING_INTERVAL_MS) {
                        Log.w(
                            TAG,
                            "frame stream disconnected from $host:$port: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                        lastWarningMillis = now
                    }
                    listener.onFrameConnectionChanged(
                        false, connectionGeneration, error.message ?: error.javaClass.simpleName,
                    )
                    try {
                        Thread.sleep(reconnectDelayMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    reconnectDelayMillis = (reconnectDelayMillis * 2L)
                        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        frameDispatcher.close()
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "OpenFlyHilFrame"
        const val MIN_RECONNECT_DELAY_MS = 500L
        const val MAX_RECONNECT_DELAY_MS = 5_000L
        const val WARNING_INTERVAL_MS = 10_000L
    }
}
