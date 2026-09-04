package edu.playground.djivln.hil

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** In-process UE UDP peer used only by explicit debug regression actions. */
class HilLoopbackPeer(private val port: Int) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val receivedPoses = AtomicLong(0L)
    private val receivedPackets = AtomicLong(0L)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openfly-hil-loopback-peer").apply { isDaemon = true }
    }
    @Volatile private var socket: DatagramSocket? = null
    private var sequence = 0L

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            socket = DatagramSocket(port, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 250
            }
            executor.execute(::receiveLoop)
            true
        } catch (_: Throwable) {
            running.set(false)
            executor.shutdownNow()
            false
        }
    }

    fun poseCount(): Long = receivedPoses.get()

    fun packetCount(): Long = receivedPackets.get()

    private fun receiveLoop() {
        val bytes = ByteArray(HilProtocol.MAX_DATAGRAM_BYTES)
        while (running.get()) {
            try {
                val packet = DatagramPacket(bytes, bytes.size)
                socket?.receive(packet) ?: break
                val datagram = HilProtocol.decode(packet.data, packet.length)
                receivedPackets.incrementAndGet()
                if (datagram.header.type == HilProtocol.TYPE_POSE) {
                    HilProtocol.decodePose(datagram.payload)
                    receivedPoses.incrementAndGet()
                }
                val reply = if (datagram.header.type == HilProtocol.TYPE_PING) {
                    val ping = HilProtocol.decodePing(datagram.payload)
                    HilProtocol.encodePong(
                        datagram.header.sessionId,
                        ++sequence,
                        HilProtocol.Pong(
                            ping.senderMonotonicNanos,
                            SystemClock.elapsedRealtimeNanos(),
                        ),
                    )
                } else {
                    HilProtocol.encodeHeartbeat(
                        datagram.header.sessionId,
                        ++sequence,
                        HilProtocol.Heartbeat(
                            SystemClock.elapsedRealtimeNanos(),
                            datagram.header.sequence,
                            0,
                        ),
                    )
                }
                socket?.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (_: SocketTimeoutException) {
                Unit
            } catch (_: Throwable) {
                if (running.get()) running.set(false)
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        socket = null
        executor.shutdownNow()
    }
}
