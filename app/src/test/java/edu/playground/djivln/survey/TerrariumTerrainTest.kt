package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrariumTerrainTest {
    @Test
    fun `terrarium rgb decodes elevation in meters`() {
        val argb = 0xff000000.toInt() or (137 shl 16) or (219 shl 8) or 68
        assertEquals(2523.265625, TerrariumTile.decodeElevationMeters(argb), 1e-9)
    }

    @Test
    fun `minhang roi resolves to a bounded global tile set`() {
        val roi = listOf(
            GeoPoint(31.015, 121.420),
            GeoPoint(31.015, 121.450),
            GeoPoint(31.040, 121.450),
            GeoPoint(31.040, 121.420),
        )
        val ids = WebMercatorTileGrid.tileIdsForRoi(roi, zoom = 14)
        assertEquals(9, ids.size)
        assertTrue(ids.size <= 64)
        assertTrue(ids.all { it.zoom == 14 })
    }

    @Test
    fun `inclusive downloaded bounds do not require adjacent tiles`() {
        val id = WebMercatorTileId(14, 13718, 6705)
        val encodedZero = 0xff800000.toInt()
        val terrain = TerrariumTerrain(
            zoom = 14,
            tiles = listOf(TerrariumTile(id, IntArray(256 * 256) { encodedZero })),
        )
        assertEquals(0.0, terrain.elevationMeters(
            terrain.info.minimumLatitude, terrain.info.maximumLongitude), 1e-9)
        assertEquals(0.0, terrain.elevationMeters(
            terrain.info.maximumLatitude, terrain.info.minimumLongitude), 1e-9)
    }

    @Test
    fun `tile bounds contain source coordinate`() {
        val longitude = 121.436
        val latitude = 31.025
        val zoom = 14
        val x = WebMercatorTileGrid.tileX(longitude, zoom)
        val y = WebMercatorTileGrid.tileY(latitude, zoom)
        assertTrue(longitude >= WebMercatorTileGrid.longitudeAtTileX(x, zoom))
        assertTrue(longitude <= WebMercatorTileGrid.longitudeAtTileX(x + 1, zoom))
        assertTrue(latitude <= WebMercatorTileGrid.latitudeAtTileY(y, zoom))
        assertTrue(latitude >= WebMercatorTileGrid.latitudeAtTileY(y + 1, zoom))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized roi fails closed before network access`() {
        WebMercatorTileGrid.tileIdsForRoi(
            listOf(
                GeoPoint(20.0, 100.0),
                GeoPoint(20.0, 130.0),
                GeoPoint(40.0, 130.0),
                GeoPoint(40.0, 100.0),
            ),
            zoom = 14,
        )
    }
}
