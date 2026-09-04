package edu.playground.djivln.domain.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreviewGeometryTest {
    @Test fun `four three PIP retains full frame without growing the overlay`() {
        assertEquals(VideoPreviewGeometry.Size(180, 135),
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(1440, 1080), 240, 135))
    }

    @Test fun `sixteen nine and square streams use their own aspect ratio`() {
        assertEquals(VideoPreviewGeometry.Size(240, 135),
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(1920, 1080), 240, 135))
        assertEquals(VideoPreviewGeometry.Size(135, 135),
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(640, 640), 240, 135))
    }

    @Test fun `portrait stream and resolution switch do not stretch`() {
        assertEquals(VideoPreviewGeometry.Size(76, 135),
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(1080, 1920), 240, 135))
        assertEquals(
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(1440, 1080), 240, 135),
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(4032, 3024), 240, 135),
        )
    }

    @Test fun `full screen fit preserves four three content with side margins`() {
        assertEquals(VideoPreviewGeometry.Size(1707, 1280),
            VideoPreviewGeometry.fitInside(VideoPreviewGeometry.Size(1440, 1080), 2772, 1280))
    }
}
