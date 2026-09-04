package edu.playground.djivln.reconstruction

import android.content.Context
import android.os.Handler
import android.os.Looper
import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText
import edu.playground.djivln.survey.SurveyFrameMetadata
import edu.playground.djivln.survey.SurveyPhotoTrigger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class V86StreamingController(
    context: Context,
    private val onChanged: (Snapshot) -> Unit,
) : AutoCloseable {
    data class Snapshot(
        val endpoint: String = V86HttpClient.DEFAULT_ENDPOINT,
        val accessCodeStored: Boolean = false,
        val sessionId: String? = null,
        val phase: String = "idle",
        val message: UiText = UiText.resource(R.string.v86_no_cloud_session),
        val imageCount: Int = 0,
        val pendingCount: Int = 0,
        val uploadedCount: Int = 0,
        val sealed: Boolean = false,
        val running: Boolean = false,
        val completed: Boolean = false,
        val previewReady: Boolean = false,
        val progress: Double = 0.0,
        val fastSfmCompletedImages: Int = 0,
        val fastSfmTargetImages: Int = 0,
        val fastSfmResumeFrom: Int = 0,
        val sfmLanePhase: String = "idle",
        val sfmLaneMessage: String = "",
        val scal3rLanePhase: String = "idle",
        val scal3rLaneMessage: String = "",
        val scal3rLaneSnapshotImages: Int = 0,
        val scal3rLaneTargetWindows: Int = 0,
        val scal3rLaneCompletedWindows: Int = 0,
        val scal3rLaneCacheHits: Int = 0,
        val pointCloudUrl: String? = null,
        val viewerDataUrl: String? = null,
        val missionUrl: String? = null,
        val detectorCounts: V86DetectorCounts = V86DetectorCounts(),
        val safeToExecute: Boolean = false,
        val lastError: String? = null,
        val busy: Boolean = false,
        val uploadRetryAttempt: Int = 0,
        val nextUploadRetryAtEpochMillis: Long = 0L,
        val lastSuccessfulContactEpochMillis: Long = 0L,
        val captureRejectedCount: Int = 0,
        val lastCaptureWarning: String? = null,
        val takeoffAbsoluteAltitudeMeters: Double? = null,
        val relativeHeightTest: Boolean = false,
    )

    private data class QueueItem(
        val sequence: Int,
        val fileName: String,
        val displayName: String,
        val mimeType: String,
        val latitude: Double,
        val longitude: Double,
        val absoluteAltitudeMeters: Double?,
        val timestamp: String,
        val captureView: String,
        val useTelemetryHeaders: Boolean,
        val altitudeSource: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("sequence", sequence)
            .put("file_name", fileName)
            .put("display_name", displayName)
            .put("mime_type", mimeType)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("absolute_altitude_m", absoluteAltitudeMeters ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("capture_view", captureView)
            .put("use_telemetry_headers", useTelemetryHeaders)
            .put("altitude_source", altitudeSource)

        companion object {
            fun decode(value: JSONObject) = QueueItem(
                sequence = value.getInt("sequence"),
                fileName = value.getString("file_name"),
                displayName = value.getString("display_name"),
                mimeType = value.getString("mime_type"),
                latitude = value.getDouble("latitude"),
                longitude = value.getDouble("longitude"),
                absoluteAltitudeMeters = if (value.isNull("absolute_altitude_m")) null else value.getDouble("absolute_altitude_m"),
                timestamp = value.getString("timestamp"),
                captureView = value.getString("capture_view"),
                useTelemetryHeaders = value.optBoolean("use_telemetry_headers", true),
                altitudeSource = value.optString("altitude_source", "unknown"),
            )
        }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "v86-streaming").apply { isDaemon = true }
    }
    private val root = File(appContext.filesDir, "v86-streaming").apply { mkdirs() }
    private val queueDirectory = File(root, "pending").apply { mkdirs() }
    private val stateFile = File(root, "state.json")
    private val tokenStore = V86SecureTokenStore(appContext)
    private val drainScheduled = AtomicBoolean(false)
    private val uploadRetryRunnable = Runnable {
        synchronized(lock) {
            if (closed) return@Runnable
            snapshot = snapshot.copy(nextUploadRetryAtEpochMillis = 0L)
            persistLocked()
        }
        publish()
        drainUploads()
    }
    private val lock = Any()
    private var closed = false
    private var nextSequence = 0
    private var queue = mutableListOf<QueueItem>()
    private var snapshot = Snapshot(accessCodeStored = tokenStore.load() != null)

    init {
        restore()
        publish()
        if (snapshot.sessionId != null && queue.isNotEmpty() && tokenStore.load() != null && !snapshot.sealed) {
            requestUploadDrain()
        }
    }

    fun current(): Snapshot = synchronized(lock) { snapshot }

    fun accessCode(): String = tokenStore.load().orEmpty()

    fun saveConnection(endpoint: String, accessCode: String): Result<Unit> = runCatching {
        val normalized = V86HttpClient.normalizeEndpoint(endpoint, appContext)
        require(accessCode.isNotBlank()) { text(R.string.v86_access_code_required) }
        tokenStore.save(accessCode)
        synchronized(lock) {
            snapshot = snapshot.copy(
                endpoint = normalized,
                accessCodeStored = true,
                lastError = null,
                message = if (normalized.startsWith("http://")) {
                    UiText.resource(R.string.v86_connection_saved_http_warning)
                } else {
                    UiText.resource(R.string.v86_connection_saved)
                },
            )
            persistLocked()
        }
        publish()
        if (current().pendingCount > 0) requestUploadDrain(force = true)
    }

    fun createSession(config: V86SessionConfig, callback: (Result<V86SessionState>) -> Unit = {}) {
        submit(UiText.resource(R.string.v86_creating_session), callback) {
            synchronized(lock) { require(queue.isEmpty()) { text(R.string.v86_pending_before_new_session) } }
            val client = client()
            val state = client.createSession(config)
            require(state.relativeHeightTest == config.relativeHeightTest) { text(R.string.v86_test_mode_mismatch) }
            synchronized(lock) {
                nextSequence = 0
                snapshot = Snapshot(
                    endpoint = snapshot.endpoint,
                    accessCodeStored = true,
                    sessionId = state.id,
                ).withRemote(state).copy(
                    uploadedCount = 0,
                    takeoffAbsoluteAltitudeMeters = config.takeoffAbsoluteAltitudeMeters,
                    lastSuccessfulContactEpochMillis = System.currentTimeMillis(),
                )
                persistLocked()
            }
            state
        }
    }

    /**
     * Applies the same altitude contract used by the upload queue before EXIF is written. This
     * keeps the phone copy and the server request identical when an aircraft exposes relative
     * altitude but does not expose ASL (for example some consumer-aircraft SDK combinations).
     */
    fun enrichCaptureMetadata(metadata: SurveyFrameMetadata): SurveyFrameMetadata {
        if (current().relativeHeightTest) return metadata.copy(
            altitudeAboveSeaLevelMeters = null, altitudeAboveSeaLevelSource = null,
        )
        val altitude = V86CaptureAltitudePolicy.resolve(
            frameAbsoluteAltitudeMeters = metadata.altitudeAboveSeaLevelMeters,
            relativeAltitudeMeters = metadata.relativeAltitudeMeters,
            takeoffAbsoluteAltitudeMeters = current().takeoffAbsoluteAltitudeMeters,
            frameAbsoluteAltitudeSource = metadata.altitudeAboveSeaLevelSource,
        ) ?: return metadata
        return metadata.copy(
            altitudeAboveSeaLevelMeters = altitude.absoluteAltitudeMeters,
            altitudeAboveSeaLevelSource = altitude.source,
        )
    }

    fun enqueueCapture(
        trigger: SurveyPhotoTrigger,
        metadata: SurveyFrameMetadata,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<Int> {
        val resolvedMetadata = enrichCaptureMetadata(metadata)
        if (current().relativeHeightTest) {
            if (!resolvedMetadata.hasFreshAircraftGps || resolvedMetadata.relativeAltitudeMeters?.isFinite() != true) {
                val error = IllegalStateException(text(R.string.v86_relative_live_metadata_missing))
                reportCaptureRejected(error.message.orEmpty())
                return Result.failure(error)
            }
            return enqueueImage(
                displayName, mimeType, bytes, requireNotNull(resolvedMetadata.latitude),
                requireNotNull(resolvedMetadata.longitude), null, "image_exif",
                isoTimestamp(resolvedMetadata.frameEpochMillis), serverCaptureView(trigger.captureView),
                useTelemetryHeaders = false,
                relativeHeightTest = true,
                relativeAltitudeMeters = resolvedMetadata.relativeAltitudeMeters,
            )
        }
        val validated = runCatching {
            require(resolvedMetadata.hasFreshAircraftGps) { text(R.string.v86_image_fresh_gps_required) }
            val altitude = V86CaptureAltitudePolicy.resolve(
                frameAbsoluteAltitudeMeters = resolvedMetadata.altitudeAboveSeaLevelMeters,
                relativeAltitudeMeters = resolvedMetadata.relativeAltitudeMeters,
                takeoffAbsoluteAltitudeMeters = current().takeoffAbsoluteAltitudeMeters,
                frameAbsoluteAltitudeSource = resolvedMetadata.altitudeAboveSeaLevelSource,
            )
            requireNotNull(altitude) {
                text(R.string.v86_image_asl_required)
            }
            Triple(
                requireNotNull(resolvedMetadata.latitude),
                requireNotNull(resolvedMetadata.longitude),
                altitude,
            )
        }
        val (latitude, longitude, altitude) = validated.getOrElse { error ->
            reportCaptureRejected(error.message ?: error.javaClass.simpleName)
            return Result.failure(error)
        }
        return enqueueImage(
            displayName = displayName,
            mimeType = mimeType,
            bytes = bytes,
            latitude = latitude,
            longitude = longitude,
            absoluteAltitudeMeters = altitude.absoluteAltitudeMeters,
            altitudeSource = altitude.source,
            timestamp = isoTimestamp(resolvedMetadata.frameEpochMillis),
            captureView = serverCaptureView(trigger.captureView),
            useTelemetryHeaders = true,
        )
    }

    fun enqueueOfflineImage(image: V86OfflineImageCompressor.PreparedImage): Result<Int> = enqueueImage(
        displayName = image.displayName,
        mimeType = "image/jpeg",
        bytes = image.jpeg,
        latitude = image.latitude,
        longitude = image.longitude,
        absoluteAltitudeMeters = image.absoluteAltitudeMeters,
        altitudeSource = "image_exif",
        timestamp = image.timestamp.orEmpty(),
        captureView = image.captureView,
        useTelemetryHeaders = false,
        relativeHeightTest = image.relativeHeightTest,
        relativeAltitudeMeters = image.relativeAltitudeMeters,
    )

    private fun enqueueImage(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        latitude: Double,
        longitude: Double,
        absoluteAltitudeMeters: Double?,
        altitudeSource: String,
        timestamp: String,
        captureView: String,
        useTelemetryHeaders: Boolean,
        relativeHeightTest: Boolean = false,
        relativeAltitudeMeters: Double? = null,
    ): Result<Int> = runCatching {
        require(bytes.size >= 128) { text(R.string.v86_image_data_empty) }
        val item: QueueItem
        synchronized(lock) {
            require(!closed) { text(R.string.v86_client_closed) }
            require(snapshot.sessionId != null) { text(R.string.v86_no_cloud_session) }
            require(!snapshot.sealed) { text(R.string.v86_upload_ended) }
            require(snapshot.relativeHeightTest == relativeHeightTest) { text(R.string.v86_test_mode_mismatch) }
            require(if (relativeHeightTest) absoluteAltitudeMeters == null && relativeAltitudeMeters?.isFinite() == true
                    else absoluteAltitudeMeters?.isFinite() == true) { text(R.string.v86_image_asl_required) }
            val sequence = nextSequence++
            val extension = if (mimeType == "image/png") "png" else "jpg"
            val fileName = "%06d.%s".format(Locale.US, sequence, extension)
            val target = File(queueDirectory, fileName)
            val temporary = File(queueDirectory, "$fileName.part")
            temporary.outputStream().use { it.write(bytes) }
            require(temporary.renameTo(target)) { text(R.string.v86_queue_write_failed) }
            item = QueueItem(
                sequence = sequence,
                fileName = fileName,
                displayName = displayName,
                mimeType = mimeType,
                latitude = latitude,
                longitude = longitude,
                absoluteAltitudeMeters = absoluteAltitudeMeters,
                timestamp = timestamp,
                captureView = captureView,
                useTelemetryHeaders = useTelemetryHeaders,
                altitudeSource = altitudeSource,
            )
            queue += item
            snapshot = snapshot.copy(
                pendingCount = queue.size,
                message = UiText.resource(R.string.v86_image_queued, sequence + 1),
                lastError = null,
            )
            persistLocked()
        }
        publish()
        requestUploadDrain()
        item.sequence
    }.onFailure { error -> updateError(error.message ?: error.javaClass.simpleName) }

    fun retryUploads() {
        main.removeCallbacks(uploadRetryRunnable)
        synchronized(lock) {
            snapshot = snapshot.copy(
                lastError = null,
                message = UiText.resource(R.string.v86_retrying_pending_images, queue.size),
                uploadRetryAttempt = 0,
                nextUploadRetryAtEpochMillis = 0L,
            )
            persistLocked()
        }
        publish()
        requestUploadDrain(force = true)
    }

    fun showLocalMessage(message: String, error: String? = null) {
        synchronized(lock) {
            snapshot = snapshot.copy(message = UiText.external(message), lastError = error)
            persistLocked()
        }
        publish()
    }

    fun reportCaptureRejected(reason: String) {
        synchronized(lock) {
            if (snapshot.sessionId == null || snapshot.sealed) return
            snapshot = snapshot.copy(
                captureRejectedCount = snapshot.captureRejectedCount + 1,
                lastCaptureWarning = reason,
                message = UiText.resource(R.string.v86_capture_not_queued),
            )
            persistLocked()
        }
        publish()
    }

    fun refresh(callback: (Result<V86SessionState>) -> Unit = {}) {
        submit(UiText.resource(R.string.v86_refreshing_cloud), callback) {
            val sessionId = requireSession()
            val client = client()
            val state = client.getSession(sessionId)
            synchronized(lock) {
                snapshot = snapshot.withRemote(state).copy(
                    lastSuccessfulContactEpochMillis = System.currentTimeMillis(),
                )
                persistLocked()
            }
            if (state.completed || state.previewReady) refreshResult(client, sessionId)
            state
        }
    }

    /** Read an existing sealed task without modifying or restarting it. */
    fun openSealedSession(sessionId: String, callback: (Result<V86SessionState>) -> Unit = {}) {
        submit(UiText.resource(R.string.v86_open_existing_task), callback) {
            synchronized(lock) { require(queue.isEmpty()) { text(R.string.v86_pending_images, queue.size) } }
            val client = client()
            val state = client.getSession(sessionId.trim())
            require(state.sealed) { text(R.string.v86_existing_task_must_be_sealed) }
            synchronized(lock) {
                nextSequence = state.imageCount
                snapshot = Snapshot(endpoint = snapshot.endpoint, accessCodeStored = true)
                    .withRemote(state).copy(uploadedCount = state.imageCount)
                persistLocked()
            }
            refreshResult(client, state.id)
            state
        }
    }

    fun finalizeSession(callback: (Result<V86SessionState>) -> Unit = {}) {
        submit(UiText.resource(R.string.v86_finalizing_upload), callback) {
            synchronized(lock) { require(queue.isEmpty()) { text(R.string.v86_pending_prevents_finalize, queue.size) } }
            val sessionId = requireSession()
            val state = client().finalize(sessionId)
            synchronized(lock) {
                snapshot = snapshot.withRemote(state).copy(
                    lastSuccessfulContactEpochMillis = System.currentTimeMillis(),
                )
                persistLocked()
            }
            state
        }
    }

    /**
     * Drops an unsealed session which never received a photo.  The service intentionally has no
     * delete endpoint: an empty remote session is harmless, but keeping it as the active local
     * session would otherwise trap the operator in the streaming state forever.
     */
    fun discardEmptySession(): Result<Unit> = runCatching {
        synchronized(lock) {
            require(!closed) { text(R.string.v86_client_closed) }
            require(snapshot.sessionId != null) { text(R.string.v86_no_cloud_session) }
            require(!snapshot.sealed) { text(R.string.v86_upload_ended) }
            require(queue.isEmpty()) { text(R.string.v86_pending_images, queue.size) }
            require(snapshot.imageCount == 0 && snapshot.uploadedCount == 0) {
                text(R.string.v86_uploaded_prevents_discard)
            }
            queueDirectory.listFiles()?.forEach { it.delete() }
            nextSequence = 0
            snapshot = Snapshot(
                endpoint = snapshot.endpoint,
                accessCodeStored = tokenStore.load() != null,
                message = UiText.resource(R.string.v86_empty_session_discarded),
            )
            persistLocked()
        }
    }.also { publish() }

    /** Terminates remote processing first, then removes the task and pending files locally. */
    fun terminateCurrentSession(callback: (Result<Unit>) -> Unit = {}) {
        submit(UiText.resource(R.string.v86_terminating_remote), callback) {
            val sessionId = requireSession()
            client().cancel(sessionId)
            synchronized(lock) {
                queue.clear()
                queueDirectory.listFiles()?.forEach { it.delete() }
                nextSequence = 0
                snapshot = Snapshot(
                    endpoint = snapshot.endpoint,
                    accessCodeStored = tokenStore.load() != null,
                    message = UiText.resource(R.string.v86_remote_terminated),
                )
                persistLocked()
            }
        }
    }

    fun retryProcessing(callback: (Result<V86SessionState>) -> Unit = {}) {
        submit(UiText.resource(R.string.v86_requesting_retry), callback) {
            val state = client().retry(requireSession())
            synchronized(lock) {
                snapshot = snapshot.withRemote(state).copy(
                    lastSuccessfulContactEpochMillis = System.currentTimeMillis(),
                )
                persistLocked()
            }
            state
        }
    }

    fun downloadMission(callback: (Result<String>) -> Unit) = downloadTextArtifact(
        current().missionUrl.takeUnless { current().relativeHeightTest },
        text(R.string.v86_mission_not_generated),
        callback,
    )

    fun downloadViewerData(callback: (Result<String>) -> Unit) = downloadTextArtifact(
        current().viewerDataUrl,
        text(R.string.v86_candidates_not_generated),
        callback,
    )

    fun downloadPointCloud(callback: (Result<ByteArray>) -> Unit) {
        val url = current().pointCloudUrl
        if (url == null) {
            callback(Result.failure(IllegalStateException(text(R.string.v86_point_cloud_not_generated))))
            return
        }
        submit(UiText.resource(R.string.v86_downloading_point_cloud), callback) { client().download(url) }
    }

    fun downloadPointCloudTo(
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        callback: (Result<File>) -> Unit,
    ) {
        val url = current().pointCloudUrl
        if (url == null) {
            callback(Result.failure(IllegalStateException(text(R.string.v86_point_cloud_not_generated))))
            return
        }
        submit(UiText.resource(R.string.v86_downloading_point_cloud), callback) {
            client().downloadToFile(url, target) { downloaded, total ->
                main.post { if (!closed) onProgress(downloaded, total) }
            }
        }
    }

    private fun downloadTextArtifact(url: String?, missing: String, callback: (Result<String>) -> Unit) {
        if (url == null) {
            callback(Result.failure(IllegalStateException(missing)))
            return
        }
        submit(UiText.resource(R.string.v86_downloading_result), callback) { client().download(url).toString(Charsets.UTF_8) }
    }

    private fun drainUploads() {
        if (closed || !drainScheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (!closed) {
                    val next = synchronized(lock) {
                        val item = queue.firstOrNull()
                        val sessionId = snapshot.sessionId
                        if (item == null || sessionId == null || snapshot.sealed) {
                            null
                        } else {
                            snapshot = snapshot.copy(busy = true, message = UiText.resource(R.string.v86_uploading_image, item.sequence + 1))
                            persistLocked()
                            item to sessionId
                        }
                    } ?: break
                    val (item, sessionId) = next
                    publish()
                    try {
                        client().uploadImage(
                            sessionId = sessionId,
                            sequence = item.sequence,
                            file = File(queueDirectory, item.fileName),
                            filename = item.displayName,
                            mimeType = item.mimeType,
                            latitude = item.latitude.takeIf { item.useTelemetryHeaders },
                            longitude = item.longitude.takeIf { item.useTelemetryHeaders },
                            absoluteAltitudeMeters = item.absoluteAltitudeMeters.takeIf { item.useTelemetryHeaders },
                            altitudeSource = item.altitudeSource.takeIf { item.useTelemetryHeaders },
                            // Offline timestamps come from the preserved original EXIF/XMP, not
                            // live phone telemetry, and are safe to send without changing gps_source.
                            timestamp = item.timestamp.takeIf(String::isNotBlank),
                            captureView = item.captureView,
                        )
                        synchronized(lock) {
                            queue.removeAll { it.sequence == item.sequence }
                            File(queueDirectory, item.fileName).delete()
                            snapshot = snapshot.copy(
                                pendingCount = queue.size,
                                uploadedCount = snapshot.uploadedCount + 1,
                                imageCount = maxOf(snapshot.imageCount, snapshot.uploadedCount + 1),
                                message = UiText.resource(R.string.v86_uploaded_pending, snapshot.uploadedCount + 1, queue.size),
                                lastError = null,
                                uploadRetryAttempt = 0,
                                nextUploadRetryAtEpochMillis = 0L,
                                lastSuccessfulContactEpochMillis = System.currentTimeMillis(),
                            )
                            persistLocked()
                        }
                        publish()
                    } catch (error: Throwable) {
                        val automaticallyRetryable = V86UploadRetryPolicy.isAutomaticallyRetryable(error)
                        synchronized(lock) {
                            snapshot = snapshot.copy(
                                busy = false,
                                pendingCount = queue.size,
                                lastError = error.message ?: error.javaClass.simpleName,
                                message = if (automaticallyRetryable) {
                                    UiText.resource(R.string.v86_network_retrying)
                                } else {
                                    UiText.resource(R.string.v86_upload_rejected)
                                },
                            )
                            persistLocked()
                        }
                        publish()
                        if (automaticallyRetryable) scheduleUploadRetry()
                        break
                    }
                }
            } finally {
                drainScheduled.set(false)
                synchronized(lock) {
                    if (!closed && snapshot.busy) {
                        snapshot = snapshot.copy(busy = false)
                        persistLocked()
                    }
                }
                publish()
            }
        }
    }

    private fun requestUploadDrain(force: Boolean = false) {
        val delay = synchronized(lock) {
            if (closed || queue.isEmpty() || snapshot.sealed || snapshot.sessionId == null) return
            if (force) 0L else (snapshot.nextUploadRetryAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (delay > 0L) {
            main.removeCallbacks(uploadRetryRunnable)
            main.postDelayed(uploadRetryRunnable, delay)
        } else {
            drainUploads()
        }
    }

    private fun scheduleUploadRetry() {
        val delay = synchronized(lock) {
            if (closed || queue.isEmpty() || snapshot.sealed) return
            val attempt = snapshot.uploadRetryAttempt + 1
            val retryDelay = V86UploadRetryPolicy.delayMillis(attempt)
            snapshot = snapshot.copy(
                uploadRetryAttempt = attempt,
                nextUploadRetryAtEpochMillis = System.currentTimeMillis() + retryDelay,
            )
            persistLocked()
            retryDelay
        }
        publish()
        main.removeCallbacks(uploadRetryRunnable)
        main.postDelayed(uploadRetryRunnable, delay)
    }

    private fun refreshResult(client: V86HttpClient, sessionId: String) {
        val result = client.result(sessionId)
        synchronized(lock) {
            snapshot = snapshot.copy(
                phase = result.phase,
                completed = result.completed,
                message = UiText.external(result.message),
                pointCloudUrl = result.pointCloudUrl,
                viewerDataUrl = result.viewerDataUrl,
                missionUrl = result.missionUrl.takeUnless { snapshot.relativeHeightTest || result.relativeHeightTest },
                detectorCounts = result.detectorCounts,
                safeToExecute = !snapshot.relativeHeightTest && !result.relativeHeightTest && result.safeToExecute,
                relativeHeightTest = snapshot.relativeHeightTest || result.relativeHeightTest,
                lastError = result.error ?: result.missionError.takeUnless { result.relativeHeightTest },
                lastSuccessfulContactEpochMillis = System.currentTimeMillis(),
            )
            persistLocked()
        }
        publish()
    }

    private fun client(): V86HttpClient {
        val token = tokenStore.load() ?: error(text(R.string.v86_access_code_not_configured))
        return V86HttpClient(current().endpoint, token, appContext)
    }

    private fun requireSession(): String = current().sessionId ?: error(text(R.string.v86_no_cloud_session))

    private fun <T> submit(
        busyMessage: UiText,
        callback: (Result<T>) -> Unit,
        block: () -> T,
    ) {
        synchronized(lock) {
            if (closed) {
                callback(Result.failure(IllegalStateException(text(R.string.v86_client_closed))))
                return
            }
            snapshot = snapshot.copy(busy = true, message = busyMessage, lastError = null)
            persistLocked()
        }
        publish()
        worker.execute {
            val result = runCatching(block)
            synchronized(lock) {
                snapshot = if (result.isSuccess) {
                    snapshot.copy(busy = false)
                } else {
                    snapshot.copy(
                        busy = false,
                        message = UiText.resource(R.string.operation_failed),
                        lastError = result.exceptionOrNull()?.message ?: text(R.string.unknown_error),
                    )
                }
                persistLocked()
            }
            publish()
            main.post { callback(result) }
        }
    }

    private fun Snapshot.withRemote(state: V86SessionState): Snapshot = copy(
        sessionId = state.id,
        phase = state.phase,
        progress = state.progress,
        message = UiText.external(state.message),
        imageCount = state.imageCount,
        sealed = state.sealed,
        running = state.running,
        completed = state.completed,
        previewReady = state.previewReady,
        fastSfmCompletedImages = state.fastSfmCompletedImages,
        fastSfmTargetImages = state.fastSfmTargetImages,
        fastSfmResumeFrom = state.fastSfmResumeFrom,
        sfmLanePhase = state.sfmLanePhase,
        sfmLaneMessage = state.sfmLaneMessage,
        scal3rLanePhase = state.scal3rLanePhase,
        scal3rLaneMessage = state.scal3rLaneMessage,
        scal3rLaneSnapshotImages = state.scal3rLaneSnapshotImages,
        scal3rLaneTargetWindows = state.scal3rLaneTargetWindows,
        scal3rLaneCompletedWindows = state.scal3rLaneCompletedWindows,
        scal3rLaneCacheHits = state.scal3rLaneCacheHits,
        takeoffAbsoluteAltitudeMeters = if (state.relativeHeightTest) null else state.takeoffAbsoluteAltitudeMeters
            ?: takeoffAbsoluteAltitudeMeters,
        relativeHeightTest = state.relativeHeightTest,
        lastError = state.error,
    )

    private fun updateError(message: String) {
        synchronized(lock) {
            snapshot = snapshot.copy(lastError = message, message = UiText.external(message))
            persistLocked()
        }
        publish()
    }

    private fun publish() {
        val copy = synchronized(lock) { snapshot.copy(pendingCount = queue.size) }
        main.post { if (!closed) onChanged(copy) }
    }

    private fun restore() = synchronized(lock) {
        if (!stateFile.isFile) return@synchronized
        runCatching {
            val value = JSONObject(stateFile.readText())
            val rows = value.optJSONArray("queue") ?: JSONArray()
            queue = buildList {
                for (index in 0 until rows.length()) {
                    val item = QueueItem.decode(rows.getJSONObject(index))
                    if (File(queueDirectory, item.fileName).isFile) add(item)
                }
            }.toMutableList()
            nextSequence = value.optInt("next_sequence", (queue.maxOfOrNull { it.sequence } ?: -1) + 1)
            snapshot = Snapshot(
                endpoint = value.optString("endpoint", V86HttpClient.DEFAULT_ENDPOINT),
                accessCodeStored = tokenStore.load() != null,
                sessionId = value.optNullableString("session_id"),
                phase = value.optString("phase", "idle"),
                message = UiText.resource(R.string.v86_restored_session),
                imageCount = value.optInt("image_count", 0),
                pendingCount = queue.size,
                uploadedCount = value.optInt("uploaded_count", 0),
                sealed = value.optBoolean("sealed", false),
                running = value.optBoolean("running", false),
                completed = value.optBoolean("completed", false),
                previewReady = value.optBoolean("preview_ready", false),
                progress = value.optDouble("progress", 0.0),
                fastSfmCompletedImages = value.optInt("fast_sfm_completed_images", 0),
                fastSfmTargetImages = value.optInt("fast_sfm_target_images", 0),
                fastSfmResumeFrom = value.optInt("fast_sfm_resume_from", 0),
                sfmLanePhase = value.optString("sfm_lane_phase", "idle"),
                sfmLaneMessage = value.optString("sfm_lane_message", ""),
                scal3rLanePhase = value.optString("scal3r_lane_phase", "idle"),
                scal3rLaneMessage = value.optString("scal3r_lane_message", ""),
                scal3rLaneSnapshotImages = value.optInt("scal3r_lane_snapshot_images", 0),
                scal3rLaneTargetWindows = value.optInt("scal3r_lane_target_windows", 0),
                scal3rLaneCompletedWindows = value.optInt("scal3r_lane_completed_windows", 0),
                scal3rLaneCacheHits = value.optInt("scal3r_lane_cache_hits", 0),
                pointCloudUrl = value.optNullableString("point_cloud_url"),
                viewerDataUrl = value.optNullableString("viewer_data_url"),
                missionUrl = value.optNullableString("mission_url"),
                detectorCounts = V86DetectorCounts(
                    v50TierA = value.optInt("v50_tier_a", 0),
                    v50TierB = value.optInt("v50_tier_b", 0),
                    v78 = value.optInt("v78", 0),
                    v78Selected = value.optInt("v78_selected", 0),
                ),
                safeToExecute = value.optBoolean("safe_to_execute", false),
                lastError = value.optNullableString("last_error"),
                uploadRetryAttempt = value.optInt("upload_retry_attempt", 0),
                nextUploadRetryAtEpochMillis = value.optLong("next_upload_retry_at_epoch_ms", 0L),
                lastSuccessfulContactEpochMillis = value.optLong("last_successful_contact_epoch_ms", 0L),
                captureRejectedCount = value.optInt("capture_rejected_count", 0),
                relativeHeightTest = value.optBoolean("relative_height_test", false),
                lastCaptureWarning = value.optNullableString("last_capture_warning"),
                takeoffAbsoluteAltitudeMeters = if (value.isNull("takeoff_absolute_altitude_m")) {
                    null
                } else {
                    value.optDouble("takeoff_absolute_altitude_m").takeIf(Double::isFinite)
                },
            )
        }.onFailure {
            stateFile.renameTo(File(root, "state-corrupt-${System.currentTimeMillis()}.json"))
            queue = mutableListOf()
            nextSequence = 0
        }
    }

    private fun persistLocked() {
        root.mkdirs()
        val value = JSONObject()
            .put("endpoint", snapshot.endpoint)
            .put("session_id", snapshot.sessionId ?: JSONObject.NULL)
            .put("phase", snapshot.phase)
            .put("image_count", snapshot.imageCount)
            .put("uploaded_count", snapshot.uploadedCount)
            .put("sealed", snapshot.sealed)
            .put("running", snapshot.running)
            .put("completed", snapshot.completed)
            .put("preview_ready", snapshot.previewReady)
            .put("progress", snapshot.progress)
            .put("fast_sfm_completed_images", snapshot.fastSfmCompletedImages)
            .put("fast_sfm_target_images", snapshot.fastSfmTargetImages)
            .put("fast_sfm_resume_from", snapshot.fastSfmResumeFrom)
            .put("sfm_lane_phase", snapshot.sfmLanePhase)
            .put("sfm_lane_message", snapshot.sfmLaneMessage)
            .put("scal3r_lane_phase", snapshot.scal3rLanePhase)
            .put("scal3r_lane_message", snapshot.scal3rLaneMessage)
            .put("scal3r_lane_snapshot_images", snapshot.scal3rLaneSnapshotImages)
            .put("scal3r_lane_target_windows", snapshot.scal3rLaneTargetWindows)
            .put("scal3r_lane_completed_windows", snapshot.scal3rLaneCompletedWindows)
            .put("scal3r_lane_cache_hits", snapshot.scal3rLaneCacheHits)
            .put("point_cloud_url", snapshot.pointCloudUrl ?: JSONObject.NULL)
            .put("viewer_data_url", snapshot.viewerDataUrl ?: JSONObject.NULL)
            .put("mission_url", snapshot.missionUrl ?: JSONObject.NULL)
            .put("v50_tier_a", snapshot.detectorCounts.v50TierA)
            .put("v50_tier_b", snapshot.detectorCounts.v50TierB)
            .put("v78", snapshot.detectorCounts.v78)
            .put("v78_selected", snapshot.detectorCounts.v78Selected)
            .put("safe_to_execute", snapshot.safeToExecute)
            .put("last_error", snapshot.lastError ?: JSONObject.NULL)
            .put("upload_retry_attempt", snapshot.uploadRetryAttempt)
            .put("next_upload_retry_at_epoch_ms", snapshot.nextUploadRetryAtEpochMillis)
            .put("last_successful_contact_epoch_ms", snapshot.lastSuccessfulContactEpochMillis)
            .put("capture_rejected_count", snapshot.captureRejectedCount)
            .put("last_capture_warning", snapshot.lastCaptureWarning ?: JSONObject.NULL)
            .put("takeoff_absolute_altitude_m", snapshot.takeoffAbsoluteAltitudeMeters ?: JSONObject.NULL)
            .put("relative_height_test", snapshot.relativeHeightTest)
            .put("next_sequence", nextSequence)
            .put("queue", JSONArray().apply { queue.forEach { put(it.toJson()) } })
        val temporary = File(root, "state.json.part")
        temporary.writeText(value.toString())
        if (!temporary.renameTo(stateFile)) {
            temporary.copyTo(stateFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun text(resourceId: Int, vararg arguments: Any?): String =
        appContext.getString(resourceId, *arguments)

    override fun close() {
        main.removeCallbacks(uploadRetryRunnable)
        synchronized(lock) {
            closed = true
            persistLocked()
        }
        worker.shutdownNow()
    }

    companion object {
        private fun isoTimestamp(epochMillis: Long): String = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMillis))

        private fun serverCaptureView(value: String?): String = when (value?.uppercase(Locale.US)) {
            "NADIR" -> "NADIR"
            "BACKWARD_OBLIQUE" -> "BACKWARD_OBLIQUE"
            "LEFT_OBLIQUE" -> "LEFT_OBLIQUE"
            "RIGHT_OBLIQUE" -> "RIGHT_OBLIQUE"
            // Schema 13 LOCAL_OBLIQUE points face their target with the forward camera axis.
            "LOCAL_OBLIQUE", "FORWARD_OBLIQUE" -> "FORWARD_OBLIQUE"
            else -> "NADIR"
        }
    }
}
