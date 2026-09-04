package edu.playground.djivln.domain.camera

class CameraSourceRouter(sources: List<CameraFrameSource>) {
    private val sourcesById = sources.associateBy { it.descriptor.id }
    @Volatile private var selectedSource: CameraFrameSource? = null
    @Volatile private var listener: CameraFrameListener? = null
    @Volatile private var latest: CameraFrame? = null

    init {
        require(sourcesById.size == sources.size) { "camera source ids must be unique" }
    }

    fun availableSources(): List<CameraSourceDescriptor> = sourcesById.values.map { it.descriptor }

    fun start(listener: CameraFrameListener) {
        this.listener = listener
        selectedSource?.start(::acceptFrame)
    }

    fun select(sourceId: String): Result<Unit> {
        val next = sourcesById[sourceId]
            ?: return Result.failure(IllegalArgumentException("unknown camera source: $sourceId"))
        if (selectedSource === next) return Result.success(Unit)
        selectedSource?.stop()
        latest = null
        selectedSource = next
        if (listener != null) next.start(::acceptFrame)
        return Result.success(Unit)
    }

    fun stop() {
        selectedSource?.stop()
        listener = null
        latest = null
    }

    fun selected(): CameraSourceDescriptor? = selectedSource?.descriptor

    fun latestFrame(): CameraFrame? = latest?.deepCopy()

    fun canRequestFrame(): Boolean = selectedSource is OnDemandCameraFrameSource

    fun requestFrame(): Result<Unit> {
        val source = selectedSource as? OnDemandCameraFrameSource
            ?: return Result.failure(IllegalStateException("selected camera source is not on-demand"))
        return source.requestFrame()
    }

    fun cancelFrameRequest() {
        (selectedSource as? OnDemandCameraFrameSource)?.cancelFrameRequest()
    }

    private fun acceptFrame(frame: CameraFrame) {
        if (frame.sourceId != selectedSource?.descriptor?.id) return
        latest = frame
        listener?.onFrame(frame)
    }
}
