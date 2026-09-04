package edu.playground.djivln.hil

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AndroidHilController(
    private val listener: Listener,
    context: Context? = null,
) : AutoCloseable {
    private val appContext = context?.applicationContext
    private enum class FrameRole { SERVER, CLIENT }
    private data class FrameConnectionToken(val role: FrameRole, val generation: Long)
    interface Listener {
        fun onHilEvent(event: HilProtocol.Event)
        fun onHilLinkStale()
        fun onHilStatus(status: Status)
        fun onHilError(message: String)
    }

    data class Config(
        val mode: HilConnectionMode = HilConnectionMode.LAN,
        val host: String,
        val udpServerPort: Int = 30_020,
        val udpLocalPort: Int = 30_021,
        val frameTcpPort: Int = 30_022,
        val poseSendHz: Int = 50,
        val simulatorStateHz: Int = 20,
        val heartbeatTimeoutMillis: Long = 1_000L,
    ) {
        init {
            require(mode == HilConnectionMode.HOTSPOT || host.isNotBlank())
            require(udpServerPort in 1..65_535 && udpLocalPort in 1..65_535 && frameTcpPort in 1..65_535)
            require(poseSendHz in 1..150 && simulatorStateHz in 2..150)
            require(heartbeatTimeoutMillis in 250L..10_000L)
        }
    }

    data class Status(
        val running: Boolean,
        val mode: HilConnectionMode,
        val peerHost: String?,
        val peerFresh: Boolean,
        val frameListening: Boolean,
        val frameConnected: Boolean,
        val framePeerHost: String?,
        val frame: HilVirtualFrameStore.Snapshot?,
        val sentPoseCount: Long,
        val receivedPacketCount: Long,
        val measuredPoseSendHz: Double,
        val roundTripMillis: Double,
        val message: UiText,
    )

    private val running = AtomicBoolean(false)
    private val peerFresh = AtomicBoolean(false)
    private val frameListening = AtomicBoolean(false)
    private val frameConnected = AtomicBoolean(false)
    private val frameServerConnected = AtomicBoolean(false)
    private val frameClientConnected = AtomicBoolean(false)
    private val latestStatus = AtomicReference<Status>()
    private val frameStore = HilVirtualFrameStore()
    private val frameConnectionLock = Any()
    private var activeFrameToken: FrameConnectionToken? = null
    private var publishedFrameGeneration = 0L
    @Volatile private var udp: HilUdpTransport? = null
    @Volatile private var frames: HilFrameStreamServer? = null
    @Volatile private var frameClient: HilFrameStreamClient? = null
    @Volatile private var frameClientHost: String? = null
    @Volatile private var activeConfig: Config? = null
    @Volatile private var peerHost: String? = null
    @Volatile private var framePeerHost: String? = null

    fun start(config: Config) {
        stop()
        running.set(true)
        activeConfig = config
        peerHost = if (config.mode == HilConnectionMode.LAN) config.host else null
        val frameServer = createFrameServer(config.frameTcpPort)
        frames = frameServer
        if (!frameServer.start()) {
            running.set(false)
            frames = null
            val status = Status(
                false, config.mode, peerHost, false, false, false, null, null,
                0L, 0L, 0.0, Double.NaN, UiText.resource(R.string.hil_tcp_listen_failed),
            )
            latestStatus.set(status)
            listener.onHilStatus(status)
            return
        }
        val udpTransport = HilUdpTransport(config, object : HilUdpTransport.Listener {
            override fun onEvent(event: HilProtocol.Event) = listener.onHilEvent(event)
            override fun onPeerDiscovered(host: String) {
                peerHost = host
                // UE already owns the healthy inbound OFFR connection in the common hotspot
                // topology. UDP rediscovery must not restart the reverse TCP role and race it.
                if (!frameServerConnected.get()) ensureFrameClient(host, config.frameTcpPort)
            }
            override fun onPeerPacket() {
                peerFresh.set(true)
            }
            override fun onLinkStale() {
                peerFresh.set(false)
                // UDP freshness gates control, not image transport. A transient heartbeat gap
                // must never cut a separately healthy TCP frame in the middle of its payload.
                // Explicit HIL stop/close still releases both transports.
                listener.onHilLinkStale()
            }
            override fun onStatus(status: HilUdpTransport.Status) {
                publishStatus(status, if (config.mode == HilConnectionMode.HOTSPOT) {
                    UiText.resource(
                        R.string.hil_hotspot_discovery_status,
                        peerHost ?: UiText.resource(R.string.hil_waiting_for_ue),
                        config.udpServerPort,
                    )
                } else {
                    UiText.resource(R.string.hil_lan_status, config.host, config.udpServerPort)
                })
            }
            override fun onTransportError(message: String) = listener.onHilError("UDP: $message")
        })
        udp = udpTransport
        if (!udpTransport.start()) {
            running.set(false)
            udp = null
            frameServer.close()
            frames = null
            val status = Status(
                false, config.mode, null, false, false, false, null, null,
                0L, 0L, 0.0, Double.NaN, UiText.resource(R.string.hil_udp_start_failed),
            )
            latestStatus.set(status)
            listener.onHilStatus(status)
            return
        }
        if (config.mode == HilConnectionMode.LAN) {
            ensureFrameClient(config.host, config.frameTcpPort)
        }
    }

    fun submitPose(pose: HilProtocol.Pose) {
        udp?.submitPose(pose)
    }

    fun decodeLatestFrame(maxAgeMillis: Long): Bitmap? = frameStore.decodeLatest(maxAgeMillis)

    fun latestFrame(maxAgeMillis: Long): HilFrameProtocol.Frame? = frameStore.latestFrame(maxAgeMillis)

    internal fun borrowLatestFrameIfNew(
        maxAgeMillis: Long,
        lastStreamGeneration: Long,
        lastFrameId: Long,
    ): HilFrameProtocol.Frame? = frameStore.borrowLatestFrameIfNew(maxAgeMillis, lastStreamGeneration, lastFrameId)

    fun frameSnapshot(): HilVirtualFrameStore.Snapshot? = frameStore.snapshot()

    fun isRunning(): Boolean = running.get()

    fun isPeerFresh(): Boolean = peerFresh.get()

    fun latestStatus(): Status? = latestStatus.get()

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val stoppedMode = activeConfig?.mode ?: HilConnectionMode.LAN
        udp?.close()
        frames?.close()
        frameClient?.close()
        udp = null
        frames = null
        frameClient = null
        frameClientHost = null
        activeConfig = null
        peerHost = null
        framePeerHost = null
        peerFresh.set(false)
        frameListening.set(false)
        frameConnected.set(false)
        frameServerConnected.set(false)
        frameClientConnected.set(false)
        synchronized(frameConnectionLock) {
            activeFrameToken = null
            publishedFrameGeneration = 0L
        }
        frameStore.clear()
        val status = Status(
            false, stoppedMode, null, false, false, false, null, null,
            0L, 0L, 0.0, Double.NaN, UiText.resource(R.string.hil_stopped),
        )
        latestStatus.set(status)
        listener.onHilStatus(status)
    }

    private fun publishStatus(status: HilUdpTransport.Status, message: UiText) {
        if (!running.get()) return
        val value = Status(
            true,
            activeConfig?.mode ?: HilConnectionMode.LAN,
            peerHost,
            peerFresh.get(),
            frameListening.get(),
            frameConnected.get(),
            framePeerHost,
            frameStore.snapshot(SystemClock.elapsedRealtimeNanos()),
            status.sentPoseCount,
            status.receivedPacketCount,
            status.measuredPoseSendHz,
            status.roundTripMillis,
            message,
        )
        latestStatus.set(value)
        listener.onHilStatus(value)
    }

    private fun createFrameServer(port: Int): HilFrameStreamServer {
        return HilFrameStreamServer(port, { peerHost }, object : HilFrameStreamServer.Listener {
            override fun onFrame(frame: HilFrameProtocol.Frame, connectionGeneration: Long) {
                acceptFrame(FrameConnectionToken(FrameRole.SERVER, connectionGeneration), frame)
            }
            override fun onListeningChanged(listening: Boolean, message: String) {
                frameListening.set(listening)
                latestStatus.get()?.copy(
                    frameListening = listening,
                    message = UiText.resource(R.string.hil_tcp_listener_detail, UiText.external(message)),
                )?.let { updated ->
                    latestStatus.set(updated)
                    listener.onHilStatus(updated)
                }
            }
            override fun onFrameConnectionChanged(
                connected: Boolean,
                peerHost: String?,
                connectionGeneration: Long,
                message: String,
            ) {
                frameServerConnected.set(connected)
                if (connected) stopOutboundFrameClientForInboundConnection()
                updateFrameConnection(
                    FrameConnectionToken(FrameRole.SERVER, connectionGeneration),
                    connected,
                    peerHost,
                    UiText.resource(R.string.hil_tcp_inbound_detail, UiText.external(message)),
                )
            }
        }, appContext)
    }

    @Synchronized
    private fun ensureFrameClient(host: String, port: Int) {
        if (!running.get() || host.isBlank()) return
        if (frameClientHost == host && frameClient != null) return
        frameClient?.close()
        frameClient = null
        frameClientHost = host
        frameClientConnected.set(false)
        val client = HilFrameStreamClient(host, port, object : HilFrameStreamClient.Listener {
            override fun onFrame(frame: HilFrameProtocol.Frame, connectionGeneration: Long) {
                acceptFrame(FrameConnectionToken(FrameRole.CLIENT, connectionGeneration), frame)
            }

            override fun onFrameConnectionChanged(connected: Boolean, connectionGeneration: Long, message: String) {
                frameClientConnected.set(connected)
                updateFrameConnection(
                    FrameConnectionToken(FrameRole.CLIENT, connectionGeneration),
                    connected,
                    if (connected) host else null,
                    UiText.resource(R.string.hil_tcp_outbound_detail, UiText.external(message)),
                )
            }
        })
        frameClient = client
        client.start()
    }

    /** UE already pushes OFFR frames to our listener; do not keep racing the reverse role. */
    @Synchronized
    private fun stopOutboundFrameClientForInboundConnection() {
        frameClient?.close()
        frameClient = null
        frameClientHost = null
        frameClientConnected.set(false)
    }

    private fun acceptFrame(token: FrameConnectionToken, frame: HilFrameProtocol.Frame) {
        val publicationGeneration = synchronized(frameConnectionLock) {
            if (activeFrameToken == null) {
                activeFrameToken = token
                publishedFrameGeneration += 1L
                frameStore.clear()
                frameConnected.set(true)
                framePeerHost = if (token.role == FrameRole.CLIENT) frameClientHost else peerHost
            }
            if (activeFrameToken != token) return
            publishedFrameGeneration
        }
        frameStore.offer(frame.copy(streamGeneration = publicationGeneration))
    }

    private fun updateFrameConnection(
        token: FrameConnectionToken,
        connected: Boolean,
        candidatePeerHost: String?,
        message: UiText,
    ) {
        synchronized(frameConnectionLock) {
            if (connected) {
                if (activeFrameToken != token) {
                    activeFrameToken = token
                    publishedFrameGeneration += 1L
                    frameStore.clear()
                }
                frameConnected.set(true)
                framePeerHost = candidatePeerHost
                    ?: if (token.role == FrameRole.CLIENT) frameClientHost else peerHost
            } else if (activeFrameToken == token) {
                activeFrameToken = null
                publishedFrameGeneration += 1L
                frameStore.clear()
                frameConnected.set(false)
                framePeerHost = null
            }
        }
        latestStatus.get()?.copy(
            frameConnected = frameConnected.get(),
            framePeerHost = framePeerHost,
            frame = frameStore.snapshot(),
            message = message,
        )?.let { updated ->
            latestStatus.set(updated)
            listener.onHilStatus(updated)
        }
    }

    override fun close() = stop()
}
