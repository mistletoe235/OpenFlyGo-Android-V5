package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.camera.CameraFrame
import edu.playground.djivln.domain.camera.CameraFrameListener
import edu.playground.djivln.domain.camera.CameraFrameSource
import edu.playground.djivln.domain.camera.CameraSourceDescriptor
import edu.playground.djivln.domain.camera.CameraSourceKind
import edu.playground.djivln.domain.camera.FrameEncoding

class FakeCameraFrameSource(
    id: String,
    label: String = id,
    kind: CameraSourceKind = CameraSourceKind.FAKE,
    private val clockNanos: () -> Long = System::nanoTime
) : CameraFrameSource {
    override val descriptor = CameraSourceDescriptor(id, label, kind)
    @Volatile private var listener: CameraFrameListener? = null
    @Volatile private var latest: CameraFrame? = null
    private var sequence = 0L

    override fun start(listener: CameraFrameListener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
        latest = null
    }

    override fun latestFrame(): CameraFrame? = latest?.deepCopy()

    fun emitRgba(width: Int, height: Int, bytes: ByteArray = ByteArray(width * height * 4)) {
        sequence += 1L
        val frame = CameraFrame(
            sourceId = descriptor.id,
            encoding = FrameEncoding.RGBA_8888,
            bytes = bytes.copyOf(),
            width = width,
            height = height,
            capturedAtNanos = clockNanos(),
            sequence = sequence
        )
        latest = frame
        listener?.onFrame(frame)
    }
}
