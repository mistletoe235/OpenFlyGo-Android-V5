package edu.playground.djivln.domain.camera

enum class CameraSourceKind {
    DJI,
    UE_HIL,
    SAVED_FRAME,
    FAKE
}

enum class FrameEncoding {
    RGBA_8888,
    JPEG,
    PNG,
    H264,
    H265
}

data class CameraSourceDescriptor(
    val id: String,
    val label: String,
    val kind: CameraSourceKind
)

data class CameraFrame(
    val sourceId: String,
    val encoding: FrameEncoding,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtNanos: Long,
    val sequence: Long = 0L
) {
    fun deepCopy(): CameraFrame = copy(bytes = bytes.copyOf())

    override fun equals(other: Any?): Boolean =
        other is CameraFrame &&
            sourceId == other.sourceId &&
            encoding == other.encoding &&
            bytes.contentEquals(other.bytes) &&
            width == other.width &&
            height == other.height &&
            capturedAtNanos == other.capturedAtNanos &&
            sequence == other.sequence

    override fun hashCode(): Int = 31 * sourceId.hashCode() + bytes.contentHashCode()
}

fun interface CameraFrameListener {
    /** Frame bytes are read-only and may be shared with the source's latest-frame cache. */
    fun onFrame(frame: CameraFrame)
}

interface CameraFrameSource {
    val descriptor: CameraSourceDescriptor
    fun start(listener: CameraFrameListener)
    fun stop()
    fun latestFrame(): CameraFrame?
}

/**
 * A source that stays detached from the expensive decoder until a consumer
 * explicitly asks for the next frame.
 */
interface OnDemandCameraFrameSource : CameraFrameSource {
    fun requestFrame(): Result<Unit>
    fun cancelFrameRequest()
}
