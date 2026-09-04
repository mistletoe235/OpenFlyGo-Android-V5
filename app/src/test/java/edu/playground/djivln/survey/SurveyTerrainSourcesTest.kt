package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyTerrainSourcesTest {
    @Test
    fun `DEM and surface selections refer to different installed assets`() {
        val catalog = SurveyTerrainSourceCatalog()
        val bareEarth = terrain("bare-earth", 10.0)
        val surface = terrain("surface", 20.0)

        catalog.installBareEarth(bareEarth, "a".repeat(64))
        catalog.installRelativeHeightSurface(surface, "b".repeat(64))

        assertSame(surface, catalog.select(SurveyTerrainSourceKind.SURFACE_DSM).getOrThrow().source)
        assertEquals("a".repeat(64), catalog.active!!.normalizedBareEarthBaseSha256)
        assertSame(bareEarth, catalog.select(SurveyTerrainSourceKind.BARE_EARTH).getOrThrow().source)
        assertEquals("a".repeat(64), catalog.active!!.normalizedBareEarthBaseSha256)
    }

    @Test
    fun `surface digest depends only on current DEM and relative-height inputs`() {
        val catalog = SurveyTerrainSourceCatalog()
        val bareEarthSha = "a".repeat(64)
        val firstHeightSha = "b".repeat(64)
        val currentHeightSha = "c".repeat(64)
        catalog.installBareEarth(terrain("bare-earth", 10.0), bareEarthSha)
        val firstDigest = catalog.installRelativeHeightSurface(
            terrain("first-surface", 20.0),
            firstHeightSha,
        ).normalizedSha256
        val currentDigest = catalog.installRelativeHeightSurface(
            terrain("current-surface", 30.0),
            currentHeightSha,
        ).normalizedSha256

        val freshCatalog = SurveyTerrainSourceCatalog()
        freshCatalog.installBareEarth(terrain("fresh-bare-earth", 10.0), bareEarthSha)
        val freshDigest = freshCatalog.installRelativeHeightSurface(
            terrain("fresh-current-surface", 30.0),
            currentHeightSha,
        ).normalizedSha256

        assertNotEquals(firstDigest, currentDigest)
        assertEquals(freshDigest, currentDigest)
    }

    @Test
    fun `selecting a source kind that has not been loaded fails closed`() {
        val catalog = SurveyTerrainSourceCatalog()
        catalog.installStandaloneSurface(terrain("surface", 20.0), "a".repeat(64))

        assertTrue(catalog.select(SurveyTerrainSourceKind.BARE_EARTH).isFailure)
        assertEquals(SurveyTerrainSourceKind.SURFACE_DSM, catalog.selectedKind)
    }

    private fun terrain(name: String, elevation: Double) = object : TerrainElevationSource {
        override val info = TerrainRasterInfo(
            displayName = name,
            width = 2,
            height = 2,
            epsg = 4326,
            noDataValue = null,
            pixelSizeX = 1.0,
            pixelSizeY = 1.0,
            minimumLatitude = 0.0,
            maximumLatitude = 1.0,
            minimumLongitude = 0.0,
            maximumLongitude = 1.0,
        )

        override fun elevationMeters(latitude: Double, longitude: Double): Double = elevation
    }
}
