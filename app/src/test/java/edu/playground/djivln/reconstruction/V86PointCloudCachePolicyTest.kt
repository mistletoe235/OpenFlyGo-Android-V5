package edu.playground.djivln.reconstruction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class V86PointCloudCachePolicyTest {
    @Test fun previewRevisionChangesWhenStreamingGeometryAdvances() {
        val first = V86StreamingController.Snapshot(
            sessionId = "s123456",
            imageCount = 40,
            scal3rLaneCompletedWindows = 2,
        )
        val second = first.copy(imageCount = 80, scal3rLaneCompletedWindows = 5)
        assertNotEquals(V86PointCloudCachePolicy.fileStem(first), V86PointCloudCachePolicy.fileStem(second))
    }

    @Test fun finalArtifactNeverReusesPreviewCache() {
        val preview = V86StreamingController.Snapshot(sessionId = "s123456", imageCount = 80)
        val final = preview.copy(completed = true)
        assertEquals("s123456-preview-80-0", V86PointCloudCachePolicy.fileStem(preview))
        assertEquals("s123456-final", V86PointCloudCachePolicy.fileStem(final))
    }
}
