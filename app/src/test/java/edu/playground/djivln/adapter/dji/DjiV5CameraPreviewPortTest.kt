package edu.playground.djivln.adapter.dji

import org.junit.Assert.assertEquals
import org.junit.Test

class DjiV5CameraPreviewPortTest {
    @Test
    fun `keeps requested camera when stream manager reports it available`() {
        assertEquals(
            "LEFT_OR_MAIN",
            selectVideoStreamIndex(
                "LEFT_OR_MAIN",
                listOf("FPV", "LEFT_OR_MAIN"),
            ),
        )
    }

    @Test
    fun `falls back to actual stream manager camera`() {
        assertEquals(
            "FPV",
            selectVideoStreamIndex(
                "LEFT_OR_MAIN",
                listOf("FPV"),
            ),
        )
    }

    @Test
    fun `uses requested camera until availability callback arrives`() {
        assertEquals(
            "LEFT_OR_MAIN",
            selectVideoStreamIndex("LEFT_OR_MAIN", emptyList()),
        )
    }
}
