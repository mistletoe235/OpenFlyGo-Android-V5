package edu.playground.djivln.hil

import edu.playground.djivln.domain.camera.CameraFrame
import edu.playground.djivln.domain.camera.CameraFrameListener
import edu.playground.djivln.domain.camera.CameraFrameSource
import edu.playground.djivln.domain.camera.CameraSourceDescriptor
import edu.playground.djivln.domain.camera.CameraSourceKind
import edu.playground.djivln.domain.camera.FrameEncoding
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class HilCameraFrameSource(
    private val controller: AndroidHilController,
    private val maxFrameAgeMillis: Long = 1_000L,
    private val pollHz: Int = 60
) : CameraFrameSource {
    override val descriptor = CameraSourceDescriptor("ue-hil", "UE HIL Camera", CameraSourceKind.UE_HIL)
    @Volatile private var listener: CameraFrameListener? = null
    private var executor: ScheduledExecutorService? = null
    @Volatile private var latest: CameraFrame? = null
    private var lastFrameId = Long.MIN_VALUE
    private var lastStreamGeneration = Long.MIN_VALUE

    init {
        require(maxFrameAgeMillis > 0L)
        require(pollHz in 1..120)
    }

    override fun start(listener: CameraFrameListener) {
        stop()
        latest = null
        lastFrameId = Long.MIN_VALUE
        lastStreamGeneration = Long.MIN_VALUE
        this.listener = listener
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "hil-camera-source").apply { isDaemon = true }
        }.also { service ->
            service.scheduleAtFixedRate(::poll, 0L, 1_000L / pollHz, TimeUnit.MILLISECONDS)
        }
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
        listener = null
        latest = null
        lastFrameId = Long.MIN_VALUE
        lastStreamGeneration = Long.MIN_VALUE
    }

    override fun latestFrame(): CameraFrame? = latest?.deepCopy()

    private fun poll() {
        val source = controller.borrowLatestFrameIfNew(maxFrameAgeMillis, lastStreamGeneration, lastFrameId) ?: return
        if (source.streamGeneration != lastStreamGeneration) {
            lastStreamGeneration = source.streamGeneration
            lastFrameId = Long.MIN_VALUE
            latest = null
        }
        if (source.frameId <= lastFrameId) return
        lastFrameId = source.frameId
        val frame = CameraFrame(
            sourceId = descriptor.id,
            encoding = when (source.format) {
                HilFrameProtocol.FORMAT_JPEG -> FrameEncoding.JPEG
                HilFrameProtocol.FORMAT_PNG -> FrameEncoding.PNG
                else -> return
            },
            bytes = source.encoded,
            width = source.width,
            height = source.height,
            capturedAtNanos = source.receivedAndroidMonotonicNanos,
            sequence = source.frameId
        )
        latest = frame
        listener?.onFrame(frame)
    }
}
