package edu.playground.djivln.hil

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class HilUdpTransport(
    private val config: AndroidHilController.Config,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onEvent(event: HilProtocol.Event)
        fun onPeerDiscovered(host: String)
        fun onPeerPacket()
        fun onLinkStale()
        fun onStatus(status: Status)
        fun onTransportError(message: String)
    }

    data class Status(
        val sessionId: Long,
        val peerHost: String?,
        val sentPoseCount: Long,
        val receivedPacketCount: Long,
        val measuredPoseSendHz: Double,
        val roundTripMillis: Double,
    )

    private val running = AtomicBoolean(false)
    private val latestPose = AtomicReference<HilProtocol.Pose?>()
    private val sequence = AtomicLong(0L)
    private val sentPoseCount = AtomicLong(0L)
    private val receivedPacketCount = AtomicLong(0L)
    private val lastReceivedSequence = AtomicLong(0L)
    private val receivedSequenceGuard = HilPeerSequenceGuard()
    private val sendExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "openfly-hil-udp-send").apply { isDaemon = true }
    }
    private val receiveExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openfly-hil-udp-receive").apply { isDaemon = true }
    }
    private val sessionId = SecureRandom().nextLong()
    private val hotspotPeerLock = HilHotspotPeerLock()
    private val peerStateLock = Any()
    private val outboundLock = Any()
    private val watchdog = HilLinkWatchdog(TimeUnit.MILLISECONDS.toNanos(config.heartbeatTimeoutMillis))
    private val startedNanos = SystemClock.elapsedRealtimeNanos()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var remoteAddress: InetSocketAddress? = null
    @Volatile private var lastRttMillis = Double.NaN
    @Volatile private var lastHelloNanos = 0L
    private var lastTransportErrorMessage: String? = null
    private var lastTransportErrorNanos = 0L
    private var suppressedTransportErrors = 0L

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        try {
            remoteAddress = if (config.mode == HilConnectionMode.LAN) {
                InetSocketAddress(InetAddress.getByName(config.host), config.udpServerPort)
            } else {
                null
            }
            socket = DatagramSocket(config.udpLocalPort).apply {
                soTimeout = 250
                broadcast = config.mode == HilConnectionMode.HOTSPOT
                runCatching { trafficClass = 0xb8 } // Expedited forwarding where DSCP is honored.
                sendBufferSize = 128 * 1024
                receiveBufferSize = 128 * 1024
            }
        } catch (error: Throwable) {
            running.set(false)
            sendExecutor.shutdownNow()
            receiveExecutor.shutdownNow()
            reportTransportError(error.message ?: error.javaClass.simpleName, force = true)
            return false
        }
        val posePeriodNanos = 1_000_000_000L / config.poseSendHz
        sendExecutor.execute(::sendHello)
        sendExecutor.scheduleAtFixedRate(::sendLatestPose, 0L, posePeriodNanos, TimeUnit.NANOSECONDS)
        sendExecutor.scheduleAtFixedRate(
            ::sendHeartbeatAndPoll, 0L, HEARTBEAT_PERIOD_MS, TimeUnit.MILLISECONDS,
        )
        sendExecutor.scheduleAtFixedRate(::reportStatus, 100L, STATUS_PERIOD_MS, TimeUnit.MILLISECONDS)
        receiveExecutor.execute(::receiveLoop)
        return true
    }

    fun submitPose(pose: HilProtocol.Pose) {
        latestPose.set(pose)
    }

    private fun sendHello() {
        lastHelloNanos = SystemClock.elapsedRealtimeNanos()
        synchronized(outboundLock) {
            val bytes = HilProtocol.encodeHello(
                sessionId,
                sequence.incrementAndGet(),
                HilProtocol.Hello(
                    SystemClock.elapsedRealtimeNanos(), config.poseSendHz, config.simulatorStateHz,
                    config.frameTcpPort, CAPABILITY_TCP_JPEG or CAPABILITY_SAFETY_EVENTS,
                ),
            )
            if (config.mode == HilConnectionMode.HOTSPOT && remoteAddress == null) {
                discoveryTargets().forEach { sendTo(bytes, it) }
            } else {
                send(bytes)
            }
        }
    }

    private fun sendLatestPose() {
        if (!running.get()) return
        if (remoteAddress == null) return
        val pose = latestPose.get() ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val sourceAge = now - pose.sampleMonotonicNanos
        if (pose.sampleMonotonicNanos <= 0L || sourceAge !in 0L..POSE_SOURCE_TIMEOUT_NANOS) return
        synchronized(outboundLock) {
            send(HilProtocol.encodePose(sessionId, sequence.incrementAndGet(), pose))
            sentPoseCount.incrementAndGet()
        }
    }

    private fun sendHeartbeatAndPoll() {
        if (!running.get()) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!watchdog.isFresh(now) && now - lastHelloNanos >= HELLO_PERIOD_NANOS) sendHello()
        if (config.mode == HilConnectionMode.HOTSPOT && remoteAddress == null) return
        synchronized(outboundLock) {
            send(HilProtocol.encodeHeartbeat(
                sessionId,
                sequence.incrementAndGet(),
                HilProtocol.Heartbeat(now, lastReceivedSequence.get(), 0),
            ))
            send(HilProtocol.encodePing(sessionId, sequence.incrementAndGet(), HilProtocol.Ping(now)))
        }
        if (watchdog.pollStaleTransition(now)) {
            synchronized(peerStateLock) {
                receivedSequenceGuard.reset()
                lastReceivedSequence.set(0L)
                if (config.mode == HilConnectionMode.HOTSPOT) {
                    remoteAddress = null
                    hotspotPeerLock.clear()
                }
                listener.onLinkStale()
            }
            // A restarted UE process normally reuses the same IP but restarts its sequence at
            // zero. Reset the replay window in both LAN and hotspot modes, then resend HELLO so
            // the peer also rediscovers the current Android session and TCP camera port.
            sendHello()
        }
    }

    private fun receiveLoop() {
        val bytes = ByteArray(HilProtocol.MAX_DATAGRAM_BYTES)
        while (running.get()) {
            try {
                val packet = DatagramPacket(bytes, bytes.size)
                socket?.receive(packet) ?: break
                val datagram = HilProtocol.decode(packet.data, packet.length)
                if (datagram.header.sessionId != sessionId) continue
                val inbound = HilInboundMessageDecoder.decode(datagram)
                if (!acceptPeer(packet.address)) continue
                if (!acceptSequence(datagram.header.sequence)) continue
                val now = SystemClock.elapsedRealtimeNanos()
                watchdog.onReceive(now)
                lastReceivedSequence.set(datagram.header.sequence)
                receivedPacketCount.incrementAndGet()
                listener.onPeerPacket()
                when (inbound) {
                    is HilInboundMessage.Heartbeat -> Unit
                    is HilInboundMessage.Ping -> {
                        synchronized(outboundLock) {
                            send(HilProtocol.encodePong(
                                sessionId,
                                sequence.incrementAndGet(),
                                HilProtocol.Pong(inbound.value.senderMonotonicNanos, now),
                            ))
                        }
                    }
                    is HilInboundMessage.Pong -> {
                        val delta = now - inbound.value.echoedSenderMonotonicNanos
                        if (delta >= 0L) lastRttMillis = delta / 1_000_000.0
                    }
                    is HilInboundMessage.Event -> listener.onEvent(inbound.value)
                }
            } catch (_: SocketTimeoutException) {
                // Timeout exists only so close() is observed promptly.
            } catch (error: Throwable) {
                if (running.get()) reportTransportError(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun acceptPeer(address: InetAddress): Boolean = synchronized(peerStateLock) {
        val expected = remoteAddress
        if (expected != null) return@synchronized address == expected.address
        if (config.mode != HilConnectionMode.HOTSPOT) return@synchronized false
        val candidateHost = address.hostAddress ?: address.hostName
        if (!hotspotPeerLock.accept(candidateHost)) return@synchronized false
        remoteAddress = InetSocketAddress(address, config.udpServerPort)
        listener.onPeerDiscovered(candidateHost)
        true
    }

    private fun acceptSequence(candidate: Long): Boolean = synchronized(peerStateLock) {
        receivedSequenceGuard.accept(candidate)
    }

    private fun reportStatus() {
        if (!running.get()) return
        val elapsed = (SystemClock.elapsedRealtimeNanos() - startedNanos).coerceAtLeast(1L) / 1_000_000_000.0
        listener.onStatus(Status(
            sessionId,
            remoteAddress?.address?.hostAddress,
            sentPoseCount.get(),
            receivedPacketCount.get(),
            sentPoseCount.get() / elapsed,
            lastRttMillis,
        ))
    }

    private fun send(bytes: ByteArray) {
        if (!running.get()) return
        val address = remoteAddress ?: return
        sendTo(bytes, address)
    }

    private fun sendTo(bytes: ByteArray, address: InetSocketAddress) {
        if (!running.get()) return
        try {
            socket?.send(DatagramPacket(bytes, bytes.size, address))
        } catch (error: Throwable) {
            if (running.get()) reportTransportError(error.message ?: error.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun reportTransportError(message: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtimeNanos()
        val sameError = message == lastTransportErrorMessage
        if (!force && sameError && now - lastTransportErrorNanos < ERROR_REPORT_PERIOD_NANOS) {
            suppressedTransportErrors += 1L
            return
        }
        val suffix = if (suppressedTransportErrors > 0L) {
            " (suppressed $suppressedTransportErrors repeats)"
        } else {
            ""
        }
        lastTransportErrorMessage = message
        lastTransportErrorNanos = now
        suppressedTransportErrors = 0L
        listener.onTransportError(message + suffix)
    }

    private fun discoveryTargets(): Set<InetSocketAddress> {
        val addresses = linkedSetOf(InetSocketAddress("255.255.255.255", config.udpServerPort))
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(addresses) { value ->
                    value.broadcast?.let { InetSocketAddress(it, config.udpServerPort) }
                }
            }
        }
        return addresses
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        socket = null
        watchdog.reset()
        hotspotPeerLock.clear()
        sendExecutor.shutdownNow()
        receiveExecutor.shutdownNow()
    }

    private companion object {
        const val CAPABILITY_TCP_JPEG = 1
        const val CAPABILITY_SAFETY_EVENTS = 1 shl 1
        const val HEARTBEAT_PERIOD_MS = 20L
        const val STATUS_PERIOD_MS = 100L
        const val HELLO_PERIOD_NANOS = 100_000_000L
        const val POSE_SOURCE_TIMEOUT_NANOS = 1_000_000_000L
        const val ERROR_REPORT_PERIOD_NANOS = 2_000_000_000L
    }
}
