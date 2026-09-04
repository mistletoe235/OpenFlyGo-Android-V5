package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalBuildingHeightRequestTest {
    @Test
    fun `small roi resolves static grid tile and template`() {
        val tiles = GlobalBuildingHeightTiles.covering(
            listOf(
                GeoPoint(31.015, 121.420),
                GeoPoint(31.015, 121.450),
                GeoPoint(31.040, 121.450),
                GeoPoint(31.040, 121.420),
            ),
        )

        assertEquals(1, tiles.size)
        assertEquals(1507, tiles.single().x)
        assertEquals(605, tiles.single().y)
        assertEquals(
            "https://data.example/gba/1507/605/121.4_31.2_121.6_31.0.tif",
            GlobalBuildingHeightTiles.url(
                "https://data.example/gba/{x}/{y}/{west}_{north}_{east}_{south}.tif",
                tiles.single(),
            ),
        )
    }

    @Test
    fun `roi crossing grid lines resolves four tiles`() {
        val tiles = GlobalBuildingHeightTiles.covering(
            listOf(
                GeoPoint(30.99, 121.39),
                GeoPoint(30.99, 121.41),
                GeoPoint(31.01, 121.41),
                GeoPoint(31.01, 121.39),
            ),
        )

        assertEquals(4, tiles.size)
        assertEquals(setOf(1506 to 604, 1507 to 604, 1506 to 605, 1507 to 605),
            tiles.map { it.x to it.y }.toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `large building request fails before network access`() {
        GlobalBuildingHeightTiles.covering(
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(1.0, 1.0)),
        )
    }
}
