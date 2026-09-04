package edu.playground.djivln.adapter.dji

import android.os.SystemClock
import android.view.Surface
import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.remotecontroller.RequireIFrameMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import edu.playground.djivln.domain.camera.CameraFrame
import edu.playground.djivln.domain.camera.CameraFrameListener
import edu.playground.djivln.domain.camera.CameraSourceDescriptor
import edu.playground.djivln.domain.camera.CameraSourceKind
import edu.playground.djivln.domain.camera.FrameEncoding
import edu.playground.djivln.domain.camera.OnDemandCameraFrameSource
import edu.playground.djivln.domain.camera.VideoPreviewGeometry

class DjiV5CameraFrameSource(
    private val cameraIndex: ComponentIndexType,
) : OnDemandCameraFrameSource {
    override val descriptor = CameraSourceDescriptor(
        id = "dji:${cameraIndex.name}",
        label = "DJI ${cameraIndex.name}",
        kind = CameraSourceKind.DJI,
    )
    private val subscriptionToken = Any()
    @Volatile private var listener: CameraFrameListener? = null
    @Volatile private var latest: CameraFrame? = null
    @Volatile private var started = false
    @Volatile private var subscribed = false

    @Synchronized
    override fun start(listener: CameraFrameListener) {
        this.listener = listener
        started = true
    }

    override fun stop() {
        val shouldUnsubscribe = synchronized(this) {
            val active = subscribed
            subscribed = false
            started = false
            listener = null
            latest = null
            active
        }
        if (shouldUnsubscribe) DjiRgbaFrameHub.unsubscribe(cameraIndex, subscriptionToken)
    }

    override fun latestFrame(): CameraFrame? = latest?.deepCopy()

    override fun requestFrame(): Result<Unit> {
        synchronized(this) {
            if (!started || listener == null) {
                return Result.failure(IllegalStateException("camera frame source is not started"))
            }
            if (subscribed) return Result.success(Unit)
            subscribed = true
        }
        return runCatching {
            DjiRgbaFrameHub.subscribe(cameraIndex, subscriptionToken, ::acceptFrame)
        }.onFailure {
            synchronized(this) { subscribed = false }
        }
    }

    override fun cancelFrameRequest() {
        val shouldUnsubscribe = synchronized(this) {
            val active = subscribed
            subscribed = false
            active
        }
        if (shouldUnsubscribe) DjiRgbaFrameHub.unsubscribe(cameraIndex, subscriptionToken)
    }

    private fun acceptFrame(frame: CameraFrame) {
        val callback = synchronized(this) {
            if (!started || !subscribed) return
            subscribed = false
            latest = frame
            listener
        }
        DjiRgbaFrameHub.unsubscribe(cameraIndex, subscriptionToken)
        callback?.onFrame(frame)
    }
}

private object DjiRgbaFrameHub {
    private data class Feed(
        val listener: ICameraStreamManager.CameraFrameListener,
        val subscribers: LinkedHashMap<Any, (CameraFrame) -> Unit> = linkedMapOf(),
        var sequence: Long = 0L,
        var lastAcceptedAtNanos: Long = Long.MIN_VALUE,
    )

    private val streamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager
    private val lock = Any()
    private val feeds = mutableMapOf<ComponentIndexType, Feed>()

    fun subscribe(
        cameraIndex: ComponentIndexType,
        token: Any,
        callback: (CameraFrame) -> Unit,
    ) {
        synchronized(lock) {
            val existing = feeds[cameraIndex]
            if (existing != null) {
                existing.subscribers[token] = callback
                return
            }
            lateinit var feed: Feed
            val djiListener = object : ICameraStreamManager.CameraFrameListener {
                override fun onFrame(
                    frameData: ByteArray,
                    offset: Int,
                    length: Int,
                    width: Int,
                    height: Int,
                    format: ICameraStreamManager.FrameFormat,
                ) {
                    acceptFrame(cameraIndex, feed, frameData, offset, length, width, height, format)
                }
            }
            feed = Feed(djiListener)
            feed.subscribers[token] = callback
            feeds[cameraIndex] = feed
            try {
                streamManager.addFrameListener(
                    cameraIndex,
                    ICameraStreamManager.FrameFormat.RGBA_8888,
                    djiListener,
                )
            } catch (error: Throwable) {
                feeds.remove(cameraIndex)
                throw error
            }
        }
    }

    fun unsubscribe(cameraIndex: ComponentIndexType, token: Any) {
        val listenerToRemove = synchronized(lock) {
            val feed = feeds[cameraIndex] ?: return
            feed.subscribers.remove(token)
            if (feed.subscribers.isNotEmpty()) return
            feeds.remove(cameraIndex)
            feed.listener
        }
        runCatching { streamManager.removeFrameListener(listenerToRemove) }
    }

    private fun acceptFrame(
        cameraIndex: ComponentIndexType,
        feed: Feed,
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        format: ICameraStreamManager.FrameFormat,
    ) {
        if (format != ICameraStreamManager.FrameFormat.RGBA_8888 || width <= 0 || height <= 0 || offset < 0) return
        val expected = runCatching {
            Math.multiplyExact(Math.multiplyExact(width, height), RGBA_BYTES_PER_PIXEL)
        }.getOrNull() ?: return
        if (length < expected || offset > frameData.size - expected) return
        val capturedAtNanos = SystemClock.elapsedRealtimeNanos()
        val delivery = synchronized(lock) {
            if (feeds[cameraIndex] !== feed || feed.subscribers.isEmpty()) return
            if (feed.lastAcceptedAtNanos != Long.MIN_VALUE &&
                capturedAtNanos - feed.lastAcceptedAtNanos < MINIMUM_FRAME_INTERVAL_NANOS
            ) return
            feed.lastAcceptedAtNanos = capturedAtNanos
            feed.sequence += 1L
            feed.sequence to feed.subscribers.values.toList()
        }
        val frame = CameraFrame(
            sourceId = "dji:${cameraIndex.name}",
            encoding = FrameEncoding.RGBA_8888,
            bytes = frameData.copyOfRange(offset, offset + expected),
            width = width,
            height = height,
            capturedAtNanos = capturedAtNanos,
            sequence = delivery.first,
        )
        delivery.second.forEach { subscriber -> subscriber(frame) }
    }

    private const val RGBA_BYTES_PER_PIXEL = 4
    private const val MAX_FRAMES_PER_SECOND = 10
    private const val MINIMUM_FRAME_INTERVAL_NANOS = 1_000_000_000L / MAX_FRAMES_PER_SECOND
}

class DjiV5CameraPreviewPort(
    private val streamManager: ICameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager,
) {
    private var surface: Surface? = null
    private var boundCameraIndex: ComponentIndexType? = null
    @Volatile private var availableCameraIndices: List<ComponentIndexType> = emptyList()
    @Volatile private var streamEnableState: Map<ComponentIndexType, Boolean> = emptyMap()
    @Volatile private var powerSaving = true
    @Volatile private var receivedStreamBytes = 0L
    @Volatile private var iFrameRequestStatus = "--"
    private var availabilityListener: ICameraStreamManager.AvailableCameraUpdatedListener? = null
    private var receiveStreamListener: ICameraStreamManager.ReceiveStreamListener? = null
    @Volatile private var previewGeneration = 0L
    private val previewSizeLock = Any()
    @Volatile var streamSize: VideoPreviewGeometry.Size? = null
        private set
    private var streamSizeCameraIndex: ComponentIndexType? = null

    fun startAvailabilityUpdates(onUpdated: () -> Unit) {
        if (availabilityListener != null) return
        val listener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
            override fun onAvailableCameraUpdated(availableCameraList: List<ComponentIndexType>) {
                availableCameraIndices = availableCameraList.distinct()
                onUpdated()
            }

            override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: Map<ComponentIndexType, Boolean>) {
                streamEnableState = cameraStreamEnableMap.toMap()
                onUpdated()
            }
        }
        availabilityListener = listener
        streamManager.addAvailableCameraUpdatedListener(listener)
    }

    fun setPowerSaving(enabled: Boolean) {
        powerSaving = enabled
        streamManager.setKeepAliveDecoding(!enabled)
        if (!enabled) boundCameraIndex?.let { streamManager.enableStream(it, true) }
    }

    fun bind(cameraIndex: ComponentIndexType, surface: Surface, width: Int, height: Int): ComponentIndexType {
        require(width > 0 && height > 0)
        unbind()
        val selectedIndex = selectVideoStreamIndex(cameraIndex, availableCameraIndices)
        val generation = previewGeneration
        if (streamSizeCameraIndex != selectedIndex) {
            streamSize = null
            streamSizeCameraIndex = selectedIndex
        }
        streamManager.setKeepAliveDecoding(!powerSaving)
        streamManager.enableStream(selectedIndex, true)
        receivedStreamBytes = 0L
        val listener = object : ICameraStreamManager.ReceiveStreamListener {
            override fun onReceiveStream(
                data: ByteArray,
                offset: Int,
                length: Int,
                streamInfo: dji.v5.manager.datacenter.camera.StreamInfo,
            ) {
                if (generation != previewGeneration) return
                if (length > 0 && offset >= 0 && offset <= data.size - length) {
                    receivedStreamBytes += length.toLong()
                    val streamWidth = streamInfo.width
                    val streamHeight = streamInfo.height
                    val previous = streamSize
                    if (streamWidth > 0 && streamHeight > 0 &&
                        (previous?.width != streamWidth || previous.height != streamHeight)) {
                        updateStreamSize(generation, streamWidth, streamHeight)
                    }
                }
            }
        }
        receiveStreamListener = listener
        streamManager.addReceiveStreamListener(selectedIndex, listener)
        this.surface = surface
        boundCameraIndex = selectedIndex
        streamManager.putCameraStreamSurface(
            selectedIndex,
            surface,
            width,
            height,
            // Keep the complete frame. Filling a wide phone/PIP crops 4:3 photos.
            ICameraStreamManager.ScaleType.CENTER_INSIDE,
        )
        requestIFrame(selectedIndex)
        return selectedIndex
    }

    fun requestIFrame(cameraIndex: ComponentIndexType) {
        iFrameRequestStatus = "camera:pending,rc:pending"
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(CameraKey.KeyAppRequestIFrame, cameraIndex),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(value: EmptyMsg) {
                        updateIFrameStatus("camera", "ok")
                    }

                    override fun onFailure(error: IDJIError) {
                        updateIFrameStatus("camera", "fail:${error.errorCode()}")
                    }
                },
            )
        }.onFailure { updateIFrameStatus("camera", "error:${it.javaClass.simpleName}") }
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(RemoteControllerKey.KeyRequireIFrame),
                RequireIFrameMsg(),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(value: EmptyMsg) {
                        updateIFrameStatus("rc", "ok")
                    }

                    override fun onFailure(error: IDJIError) {
                        updateIFrameStatus("rc", "fail:${error.errorCode()}")
                    }
                },
            )
        }.onFailure { updateIFrameStatus("rc", "error:${it.javaClass.simpleName}") }
    }

    @Synchronized
    private fun updateIFrameStatus(source: String, value: String) {
        val entries = iFrameRequestStatus.split(',').associate { part ->
            part.substringBefore(':') to part.substringAfter(':', "pending")
        }.toMutableMap()
        entries[source] = value
        iFrameRequestStatus =
            "internal:${entries["internal"] ?: "pending"}," +
                "camera:${entries["camera"] ?: "pending"}," +
                "rc:${entries["rc"] ?: "pending"}"
        Log.i("OpenFlyIFrame", iFrameRequestStatus)
    }

    private fun updateStreamSize(generation: Long, width: Int, height: Int) {
        synchronized(previewSizeLock) {
            if (generation == previewGeneration) streamSize = VideoPreviewGeometry.Size(width, height)
        }
    }

    /** Resize the current render target without stopping the stream or replacing listeners. */
    fun resizeSurface(target: Surface, width: Int, height: Int): Boolean {
        val index = boundCameraIndex ?: return false
        if (surface !== target || width <= 0 || height <= 0) return false
        streamManager.putCameraStreamSurface(index, target, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
        return true
    }

    fun unbind() {
        synchronized(previewSizeLock) { previewGeneration += 1L }
        surface?.let { runCatching { streamManager.removeCameraStreamSurface(it) } }
        surface = null
        receiveStreamListener?.let { runCatching { streamManager.removeReceiveStreamListener(it) } }
        receiveStreamListener = null
        val previousIndex = boundCameraIndex
        boundCameraIndex = null
        if (powerSaving && previousIndex != null) {
            runCatching { streamManager.enableStream(previousIndex, false) }
        }
    }

    fun diagnosticSummary(): String = buildString {
        append("available=")
        append(availableCameraIndices.joinToString { it.name }.ifBlank { "--" })
        append(" enabled=")
        append(streamEnableState.entries.joinToString { "${it.key.name}:${it.value}" }.ifBlank { "--" })
        append(" bound=")
        append(boundCameraIndex?.name ?: "--")
        append(" bytes=")
        append(receivedStreamBytes)
        append(" size=")
        append(streamSize?.let { "${it.width}x${it.height}" } ?: "--")
        append(" scale=CENTER_INSIDE")
        append(" iframe=")
        append(iFrameRequestStatus)
    }

    fun hasReceivedStreamData(): Boolean = receivedStreamBytes > 0L

    fun needsRebind(requested: ComponentIndexType): Boolean =
        boundCameraIndex != selectVideoStreamIndex(requested, availableCameraIndices)

    fun close() {
        unbind()
        availabilityListener?.let { streamManager.removeAvailableCameraUpdatedListener(it) }
        availabilityListener = null
    }
}

internal fun <T> selectVideoStreamIndex(
    requested: T,
    available: List<T>,
): T = when {
    requested in available -> requested
    available.isNotEmpty() -> available.first()
    else -> requested
}
