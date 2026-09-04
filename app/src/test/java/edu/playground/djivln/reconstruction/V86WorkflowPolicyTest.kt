package edu.playground.djivln.reconstruction

import edu.playground.djivln.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V86WorkflowPolicyTest {
    @Test fun completedRelativeTestDoesNotWaitForForbiddenMission() {
        val state = V86WorkflowPolicy.from(V86StreamingController.Snapshot(
            sessionId = "s-relative", sealed = true, completed = true,
            relativeHeightTest = true, pointCloudUrl = "/cloud.ply",
        ))
        assertEquals(V86WorkflowStage.RESULT_READY, state.stage)
        assertEquals(R.string.v86_relative_result_ready, state.title.resourceId)
        assertTrue(state.plyReady)
        assertFalse(state.canFinalize)
    }
    @Test fun streamingCountsIncludeDurableQueue() {
        val state = V86WorkflowPolicy.from(
            V86StreamingController.Snapshot(
                accessCodeStored = true,
                sessionId = "s123456",
                imageCount = 8,
                uploadedCount = 8,
                pendingCount = 3,
            ),
        )
        assertEquals(V86WorkflowStage.STREAMING, state.stage)
        assertEquals(R.string.v86_stream_counts, state.detail.resourceId)
        assertEquals(listOf(11, 8, 3), state.detail.arguments)
        assertFalse(state.canFinalize)
    }

    @Test fun streamingCaptureRejectionCannotLookHealthy() {
        val state = V86WorkflowPolicy.from(
            V86StreamingController.Snapshot(
                sessionId = "s-live",
                imageCount = 5,
                captureRejectedCount = 2,
                lastCaptureWarning = "等待 DJI 图传帧超时",
            ),
        )
        assertEquals(V86WorkflowStage.STREAMING, state.stage)
        assertEquals(R.string.v86_stream_rejected, state.title.resourceId)
        assertEquals(listOf(2), state.title.arguments)
        assertTrue(state.needsAttention)
    }

    @Test fun emptyStreamingSessionCanBeDiscardedButNotFinalized() {
        val state = V86WorkflowPolicy.from(
            V86StreamingController.Snapshot(
                accessCodeStored = true,
                sessionId = "s-empty",
            ),
        )
        assertFalse(state.canFinalize)
        assertTrue(state.canDiscardEmpty)
    }

    @Test fun queuedFailureIsRecoverableAndNeverFinalizable() {
        val state = V86WorkflowPolicy.from(
            V86StreamingController.Snapshot(
                accessCodeStored = true,
                sessionId = "s123456",
                imageCount = 8,
                pendingCount = 2,
                lastError = "timeout",
                nextUploadRetryAtEpochMillis = System.currentTimeMillis() + 5_000,
            ),
        )
        assertEquals(V86WorkflowStage.UPLOAD_RETRY, state.stage)
        assertTrue(state.needsAttention)
        assertFalse(state.canFinalize)
    }

    @Test fun pointCloudAndMissionProduceReadyState() {
        val state = V86WorkflowPolicy.from(
            V86StreamingController.Snapshot(
                accessCodeStored = true,
                sessionId = "s123456",
                sealed = true,
                completed = true,
                imageCount = 40,
                pointCloudUrl = "/cloud.ply",
                missionUrl = "/mission.json",
            ),
        )
        assertEquals(V86WorkflowStage.RESULT_READY, state.stage)
        assertEquals(R.string.v86_ply_view, state.plyButtonLabel.resourceId)
        assertTrue(state.plyReady)
    }
}
