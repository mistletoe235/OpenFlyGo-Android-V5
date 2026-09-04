package edu.playground.djivln.reconstruction

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

enum class V86WorkflowStage {
    NOT_READY,
    READY,
    STREAMING,
    UPLOAD_RETRY,
    FINALIZING,
    PROCESSING,
    PLY_READY,
    RESULT_READY,
    ERROR,
}

data class V86WorkflowState(
    val stage: V86WorkflowStage,
    val title: UiText,
    val detail: UiText,
    val progress: Double?,
    val plyButtonLabel: UiText,
    val plyReady: Boolean,
    val canFinalize: Boolean,
    val canDiscardEmpty: Boolean,
    val needsAttention: Boolean,
)

/** Product-facing state derived from the detailed server/upload contract. */
object V86WorkflowPolicy {
    fun from(snapshot: V86StreamingController.Snapshot): V86WorkflowState {
        val queued = snapshot.pendingCount
        val uploaded = maxOf(snapshot.imageCount, snapshot.uploadedCount)
        val captured = uploaded + queued
        val plyReady = !snapshot.pointCloudUrl.isNullOrBlank()
        val hasError = !snapshot.lastError.isNullOrBlank()
        val scal3rProgress = if (snapshot.scal3rLaneTargetWindows > 0) {
            snapshot.scal3rLaneCompletedWindows.toDouble() / snapshot.scal3rLaneTargetWindows
        } else null
        val processingProgress = scal3rProgress ?: snapshot.progress.takeIf { it > 0.0 }
        val plyLabel = when {
            plyReady -> UiText.resource(R.string.v86_ply_view)
            snapshot.scal3rLaneTargetWindows > 0 ->
                UiText.resource(
                    R.string.v86_ply_progress,
                    snapshot.scal3rLaneCompletedWindows,
                    snapshot.scal3rLaneTargetWindows,
                )
            snapshot.sealed -> UiText.resource(R.string.v86_ply_check)
            snapshot.sessionId != null -> UiText.resource(R.string.v86_ply_pending)
            else -> UiText.external("PLY --")
        }
        return when {
            snapshot.sessionId == null -> V86WorkflowState(
                stage = if (snapshot.accessCodeStored) V86WorkflowStage.READY else V86WorkflowStage.NOT_READY,
                title = UiText.resource(
                    if (snapshot.accessCodeStored) R.string.v86_ready_title else R.string.v86_not_ready_title,
                ),
                detail = UiText.resource(R.string.v86_ready_detail),
                progress = null,
                plyButtonLabel = plyLabel,
                plyReady = false,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = !snapshot.accessCodeStored,
            )
            hasError && queued > 0 -> V86WorkflowState(
                stage = V86WorkflowStage.UPLOAD_RETRY,
                title = UiText.resource(R.string.v86_upload_deferred, queued),
                detail = if (snapshot.nextUploadRetryAtEpochMillis > 0L) {
                    UiText.resource(
                        R.string.v86_upload_auto_retry,
                        captured,
                        uploaded,
                        snapshot.retryCountdownLabel(),
                    )
                } else {
                    UiText.resource(R.string.v86_upload_check_connection, captured, uploaded)
                },
                progress = if (captured > 0) uploaded.toDouble() / captured else null,
                plyButtonLabel = plyLabel,
                plyReady = plyReady,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = true,
            )
            hasError -> V86WorkflowState(
                stage = V86WorkflowStage.ERROR,
                title = UiText.resource(R.string.v86_cloud_needs_attention),
                detail = UiText.external(snapshot.lastError.orEmpty()),
                progress = processingProgress,
                plyButtonLabel = plyLabel,
                plyReady = plyReady,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = true,
            )
            !snapshot.sealed -> V86WorkflowState(
                stage = V86WorkflowStage.STREAMING,
                title = when {
                    snapshot.captureRejectedCount > 0 ->
                        UiText.resource(R.string.v86_stream_rejected, snapshot.captureRejectedCount)
                    snapshot.busy && queued > 0 -> UiText.resource(R.string.v86_stream_uploading)
                    else -> UiText.resource(R.string.v86_stream_capturing)
                },
                detail = snapshot.lastCaptureWarning?.let {
                    UiText.resource(R.string.v86_stream_counts_warning, captured, uploaded, queued, it)
                } ?: UiText.resource(R.string.v86_stream_counts, captured, uploaded, queued),
                progress = if (captured > 0) uploaded.toDouble() / captured else 0.0,
                plyButtonLabel = plyLabel,
                plyReady = false,
                canFinalize = queued == 0 && captured > 0 && !snapshot.busy,
                canDiscardEmpty = queued == 0 && captured == 0 && !snapshot.busy,
                needsAttention = snapshot.captureRejectedCount > 0,
            )
            plyReady && snapshot.relativeHeightTest && snapshot.completed -> V86WorkflowState(
                stage = V86WorkflowStage.RESULT_READY,
                title = UiText.resource(R.string.v86_relative_result_ready),
                detail = UiText.resource(R.string.v86_relative_test_warning),
                progress = 1.0,
                plyButtonLabel = plyLabel,
                plyReady = true,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = false,
            )
            plyReady && !snapshot.missionUrl.isNullOrBlank() -> V86WorkflowState(
                stage = V86WorkflowStage.RESULT_READY,
                title = UiText.resource(R.string.v86_result_ready_title),
                detail = UiText.resource(R.string.v86_result_ready_detail, uploaded, snapshot.detectorCounts.v78Selected),
                progress = 1.0,
                plyButtonLabel = plyLabel,
                plyReady = true,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = false,
            )
            plyReady -> V86WorkflowState(
                stage = V86WorkflowStage.PLY_READY,
                title = UiText.resource(R.string.v86_ply_ready_title),
                detail = UiText.resource(R.string.v86_ply_ready_detail),
                progress = 1.0,
                plyButtonLabel = plyLabel,
                plyReady = true,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = false,
            )
            snapshot.running || snapshot.phase !in setOf("idle", "complete", "completed") -> V86WorkflowState(
                stage = V86WorkflowStage.PROCESSING,
                title = UiText.resource(R.string.v86_processing_title, snapshot.phaseLabel()),
                detail = snapshot.processingDetail(),
                progress = processingProgress,
                plyButtonLabel = plyLabel,
                plyReady = false,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = false,
            )
            else -> V86WorkflowState(
                stage = V86WorkflowStage.FINALIZING,
                title = UiText.resource(R.string.v86_frozen_title),
                detail = snapshot.message,
                progress = processingProgress,
                plyButtonLabel = plyLabel,
                plyReady = false,
                canFinalize = false,
                canDiscardEmpty = false,
                needsAttention = false,
            )
        }
    }

    private fun V86StreamingController.Snapshot.retryCountdownLabel(): UiText {
        val remaining = ((nextUploadRetryAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L
        return if (remaining > 0) UiText.resource(R.string.v86_retry_countdown, remaining)
        else UiText.external("")
    }

    private fun V86StreamingController.Snapshot.phaseLabel(): UiText = when (phase.lowercase()) {
        "fast_sfm" -> UiText.resource(R.string.v86_phase_fast_sfm)
        "sfm", "full_sfm" -> UiText.resource(R.string.v86_phase_full_sfm)
        "scal3r" -> UiText.resource(R.string.v86_phase_scal3r)
        "v50", "v78", "detecting" -> UiText.resource(R.string.v86_phase_detection)
        "finalizing" -> UiText.resource(R.string.v86_phase_finalizing)
        else -> if (phase.isBlank()) UiText.resource(R.string.v86_phase_preparing) else UiText.external(phase)
    }

    private fun V86StreamingController.Snapshot.processingDetail(): UiText = when {
        scal3rLaneTargetWindows > 0 ->
            UiText.resource(
                R.string.v86_scal3r_processing_detail,
                scal3rLaneCompletedWindows,
                scal3rLaneTargetWindows,
            )
        fastSfmTargetImages > 0 ->
            UiText.resource(R.string.v86_sfm_processing_detail, fastSfmCompletedImages, fastSfmTargetImages)
        else -> message
    }
}
