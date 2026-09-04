package edu.playground.djivln.survey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TerrainImportSafetyTest {
    private val roi = listOf(
        GeoPoint(0.1, 0.1),
        GeoPoint(0.1, 0.9),
        GeoPoint(0.9, 0.9),
        GeoPoint(0.9, 0.1),
    )

    @Test
    fun `bounded reader accepts exact limit and rejects the next byte`() {
        val bytes = ByteArray(17) { it.toByte() }

        assertArrayEquals(bytes, TerrainImportSafety.readBounded(ByteArrayInputStream(bytes), 17))
        assertThrows(IllegalArgumentException::class.java) {
            TerrainImportSafety.readBounded(ByteArrayInputStream(bytes), 16)
        }
    }

    @Test
    fun `coverage requires raster bounds to contain the complete ROI`() {
        val source = terrain(maximumLongitude = 0.8)

        val report = TerrainImportSafety.inspectCoverage(source, roi)

        assertFalse(report.boundsCoverRoi)
        assertFalse(report.complete)
        assertThrows(IllegalArgumentException::class.java) {
            TerrainImportSafety.requireCompleteCoverage(source, roi)
        }
    }

    @Test
    fun `coverage rejects sampled NoData holes inside the ROI`() {
        val source = terrain { latitude, longitude ->
            if (latitude in 0.45..0.55 && longitude in 0.45..0.55) Double.NaN else 12.0
        }

        val report = TerrainImportSafety.inspectCoverage(source, roi)

        assertTrue(report.boundsCoverRoi)
        assertTrue(report.missingSampleCount > 0)
        assertFalse(report.complete)
    }

    @Test
    fun `finite surface covering the complete ROI passes`() {
        val report = TerrainImportSafety.requireCompleteCoverage(terrain(), roi)

        assertTrue(report.complete)
        assertTrue(report.sampledPointCount > 0)
    }

    private fun terrain(
        maximumLongitude: Double = 1.0,
        elevation: (Double, Double) -> Double = { _, _ -> 12.0 },
    ) = object : TerrainElevationSource {
        override val info = TerrainRasterInfo(
            displayName = "test-terrain",
            width = 100,
            height = 100,
            epsg = 4326,
            noDataValue = null,
            pixelSizeX = 0.01,
            pixelSizeY = 0.01,
            minimumLatitude = 0.0,
            maximumLatitude = 1.0,
            minimumLongitude = 0.0,
            maximumLongitude = maximumLongitude,
        )

        override fun elevationMeters(latitude: Double, longitude: Double): Double =
            elevation(latitude, longitude)
    }
}
