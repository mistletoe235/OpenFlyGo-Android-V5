package edu.playground.djivln.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import edu.playground.djivln.R
import edu.playground.djivln.domain.camera.CameraFrame
import edu.playground.djivln.domain.camera.FrameEncoding
import edu.playground.djivln.survey.SurveyPhotoTrigger
import edu.playground.djivln.survey.SurveyFrameMetadata
import edu.playground.djivln.survey.SurveyFrameMetadataPolicy
import edu.playground.djivln.survey.SurveyFrameTelemetry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SurveyTriggerFrameRecorder(
    private val save: (
        directory: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        callback: (Result<String>) -> Unit,
    ) -> Unit,
    private val onEvent: (String, Map<String, Any?>) -> Unit,
    private val exifWriter: SurveyFrameExifWriter,
    private val metadataTransform: (SurveyFrameMetadata) -> SurveyFrameMetadata = { it },
    private val onSaved: (SavedFrame) -> Unit = {},
    private val onSkipped: (reason: String) -> Unit = {},
    private val context: Context? = null,
) : AutoCloseable {
    data class SavedFrame(
        val trigger: SurveyPhotoTrigger,
        val metadata: SurveyFrameMetadata,
        val displayName: String,
        val mimeType: String,
        val bytes: ByteArray,
        val savedPath: String,
        val width: Int,
        val height: Int,
        val sourceId: String,
    )

    private data class EncodedFrame(
        val bytes: ByteArray,
        val extension: String,
        val mimeType: String,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "survey-trigger-frame").apply { isDaemon = true }
    }
    private val pending = AtomicInteger(0)
    private val sequence = AtomicInteger(0)

    fun captureProvided(
        trigger: SurveyPhotoTrigger,
        frame: CameraFrame?,
        telemetry: SurveyFrameTelemetry? = null,
    ) {
        if (frame == null) {
            reportSkipped(trigger, text(R.string.trigger_frame_wait_timeout, "Timed out waiting for a DJI video frame"), "frame_wait_timeout")
            return
        }
        if (pending.incrementAndGet() > MAX_PENDING_CAPTURES) {
            pending.decrementAndGet()
            reportSkipped(trigger, text(R.string.trigger_frame_save_queue_busy, "Frame save queue is busy"), "save_queue_busy")
            return
        }
        val released = AtomicBoolean(false)
        val releaseCapture = {
            if (released.compareAndSet(false, true)) pending.decrementAndGet()
            Unit
        }
        try {
            worker.execute { captureNow(trigger, frame, telemetry, releaseCapture) }
        } catch (error: RejectedExecutionException) {
            releaseCapture()
            reportSkipped(trigger, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun captureNow(
        trigger: SurveyPhotoTrigger,
        frame: CameraFrame,
        telemetry: SurveyFrameTelemetry?,
        releaseCapture: () -> Unit,
    ) {
        var awaitingSave = false
        try {
            val sampledAtNanos = SystemClock.elapsedRealtimeNanos()
            val frameAgeMillis = (sampledAtNanos - frame.capturedAtNanos) / 1_000_000L
            val frameAfterTriggerMillis = (frame.capturedAtNanos - trigger.triggeredAtNanos) / 1_000_000L
            if (frameAgeMillis !in 0..MAX_FRAME_AGE_MILLIS || frame.capturedAtNanos < trigger.triggeredAtNanos) {
                val reason = text(R.string.trigger_frame_stale_or_before_capture, "Video frame is stale or predates the capture trigger")
                onSkipped(reason)
                onEvent(
                    "trigger_frame_skipped",
                    trigger.fields("frame_stale_or_before_trigger") + mapOf(
                        "frame_age_ms" to frameAgeMillis,
                        "frame_after_trigger_ms" to frameAfterTriggerMillis,
                        "frame_sequence" to frame.sequence,
                    ),
                )
                return
            }
            val metadata = metadataTransform(
                SurveyFrameMetadataPolicy.resolve(trigger, frame.capturedAtNanos, telemetry),
            )
            val encoded = encode(frame).let { raw ->
                if (raw.mimeType == "image/jpeg") {
                    raw.copy(bytes = exifWriter.write(raw.bytes, metadata))
                } else {
                    raw
                }
            }
            val index = sequence.incrementAndGet()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(Date(metadata.frameEpochMillis))
            val mission = trigger.missionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
            val displayName = buildString {
                append(timestamp)
                append("_")
                append(mission.ifBlank { "survey" })
                append("_p")
                append(trigger.passIndex ?: -1)
                append("_wp")
                append(trigger.waypointIndex ?: -1)
                append("_")
                append(index.toString().padStart(5, '0'))
                append(".")
                append(encoded.extension)
            }
            val frameWidth = frame.width
            val frameHeight = frame.height
            val frameSourceId = frame.sourceId
            val frameSequence = frame.sequence
            val callbackHandled = AtomicBoolean(false)
            save(DIRECTORY, displayName, encoded.mimeType, encoded.bytes) { result ->
                if (!callbackHandled.compareAndSet(false, true)) return@save
                try {
                    result.getOrNull()?.let { savedPath ->
                        onSaved(
                            SavedFrame(
                                trigger = trigger,
                                metadata = metadata,
                                displayName = displayName,
                                mimeType = encoded.mimeType,
                                bytes = encoded.bytes,
                                savedPath = savedPath,
                                width = frameWidth,
                                height = frameHeight,
                                sourceId = frameSourceId,
                            ),
                        )
                    }
                    result.exceptionOrNull()?.let { error ->
                        onSkipped(
                            context?.getString(R.string.trigger_frame_save_failed, error.message ?: error.javaClass.simpleName)
                                ?: "Video frame save failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    onEvent(
                        "trigger_frame_saved",
                        trigger.fields(result.exceptionOrNull()?.message) + mapOf(
                            "success" to result.isSuccess,
                            "saved_path" to result.getOrNull(),
                            "frame_source" to frameSourceId,
                            "frame_sequence" to frameSequence,
                            "frame_width" to frameWidth,
                            "frame_height" to frameHeight,
                            "frame_age_ms" to frameAgeMillis,
                            "frame_after_trigger_ms" to frameAfterTriggerMillis,
                            "sample_delay_ms" to ((sampledAtNanos - trigger.triggeredAtNanos) / 1_000_000L),
                            "frame_epoch_ms" to metadata.frameEpochMillis,
                            "gps_exif_written" to metadata.hasFreshAircraftGps,
                            "gps_latitude" to metadata.latitude,
                            "gps_longitude" to metadata.longitude,
                            "altitude_asl_m" to metadata.altitudeAboveSeaLevelMeters,
                            "altitude_asl_source" to metadata.altitudeAboveSeaLevelSource,
                            "gps_age_ms" to metadata.gpsAgeMillis,
                            "telemetry_after_frame_ms" to metadata.telemetryAfterFrameMillis,
                        ),
                    )
                } finally {
                    releaseCapture()
                }
            }
            awaitingSave = true
        } catch (error: Throwable) {
            reportSkipped(trigger, error.message ?: error.javaClass.simpleName)
        } finally {
            if (!awaitingSave) releaseCapture()
        }
    }

    private fun encode(frame: CameraFrame): EncodedFrame = when (frame.encoding) {
        FrameEncoding.JPEG -> EncodedFrame(frame.bytes, "jpg", "image/jpeg")
        FrameEncoding.PNG -> EncodedFrame(frame.bytes, "png", "image/png")
        FrameEncoding.RGBA_8888 -> {
            val expected = Math.multiplyExact(Math.multiplyExact(frame.width, frame.height), 4)
            require(frame.bytes.size >= expected) {
                text(R.string.trigger_frame_rgba_incomplete, "RGBA video frame data is incomplete")
            }
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.bytes, 0, expected))
                val jpeg = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        text(R.string.trigger_frame_jpeg_encode_failed, "Video frame JPEG encoding failed")
                    }
                    output.toByteArray()
                }
                EncodedFrame(jpeg, "jpg", "image/jpeg")
            } finally {
                bitmap.recycle()
            }
        }
        FrameEncoding.H264, FrameEncoding.H265 -> error(
            context?.getString(R.string.trigger_frame_not_decoded, frame.encoding)
                ?: "Video frame is not decoded: ${frame.encoding}",
        )
    }

    private fun SurveyPhotoTrigger.fields(error: String?): Map<String, Any?> = mapOf(
        "mission_id" to missionId,
        "backend" to backend.name,
        "pass_index" to passIndex,
        "waypoint_index" to waypointIndex,
        "capture_view" to captureView,
        "reason" to reason,
        "trigger_epoch_ms" to triggeredAtEpochMillis,
        "target_delay_ms" to 0L,
        "error" to error,
    )

    private fun reportSkipped(trigger: SurveyPhotoTrigger, reason: String, eventReason: String = reason) {
        onSkipped(reason)
        onEvent("trigger_frame_skipped", trigger.fields(eventReason))
    }

    private fun text(resourceId: Int, fallback: String): String = context?.getString(resourceId) ?: fallback

    override fun close() {
        worker.shutdownNow()
    }

    companion object {
        const val MAX_FRAME_AGE_MILLIS = 500L
        private const val MAX_PENDING_CAPTURES = 2
        private const val JPEG_QUALITY = 92
        private const val DIRECTORY = "survey-trigger-frames"
    }
}
