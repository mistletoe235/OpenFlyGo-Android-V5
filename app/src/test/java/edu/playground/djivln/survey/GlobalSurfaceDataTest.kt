package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSurfaceDataTest {
    @Test
    fun `global public stack is simulation only`() {
        val stack = GlobalSurfaceStack(
            terrain = GlobalSurfaceDataCatalog.COPERNICUS_DEM_GLO_30,
            buildingFootprints = GlobalSurfaceDataCatalog.OVERTURE_BUILDINGS,
            buildingHeights = GlobalSurfaceDataCatalog.GLOBAL_BUILDING_ATLAS_HEIGHT,
        )
        assertTrue(stack.terrain.globalCoverage)
        assertTrue(stack.buildingFootprints!!.globalCoverage)
        assertTrue(stack.buildingHeights!!.globalCoverage)
        assertEquals(SurfaceUseLevel.PREVIEW_AND_SIMULATION, stack.maximumUseLevel)
    }

    @Test
    fun `verified local DSM can unlock real flight`() {
        val stack = GlobalSurfaceStack(
            terrain = GlobalSurfaceDataCatalog.COPERNICUS_DEM_GLO_30,
            buildingFootprints = GlobalSurfaceDataCatalog.OVERTURE_BUILDINGS,
            buildingHeights = GlobalSurfaceDataCatalog.GLOBAL_BUILDING_ATLAS_HEIGHT,
            absoluteSurfaceOverride = GlobalSurfaceDataCatalog.userVerifiedDsm(0.1),
        )
        assertEquals(SurfaceUseLevel.REAL_FLIGHT, stack.maximumUseLevel)
    }

    @Test
    fun `relative building height is added to absolute ground`() {
        val ground = constantTerrain("ground", 8.5)
        val buildings = constantTerrain("buildings", 21.0)
        val surface = CompositeSurfaceElevationSource(ground, buildings)
        assertEquals(29.5, surface.elevationMeters(31.02, 121.43), 1e-9)
    }

    private fun constantTerrain(name: String, value: Double) = object : TerrainElevationSource {
        override val info = TerrainRasterInfo(
            displayName = name,
            width = 100,
            height = 100,
            epsg = 4326,
            noDataValue = null,
            pixelSizeX = 0.00001,
            pixelSizeY = 0.00001,
            minimumLatitude = 30.0,
            maximumLatitude = 32.0,
            minimumLongitude = 120.0,
            maximumLongitude = 122.0,
        )

        override fun elevationMeters(latitude: Double, longitude: Double) = value
    }
}
