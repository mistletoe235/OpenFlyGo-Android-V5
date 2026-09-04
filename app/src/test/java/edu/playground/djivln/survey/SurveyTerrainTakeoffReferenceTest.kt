package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyTerrainTakeoffReferenceTest {
    private val referencePoint = GeoPoint(31.0, 121.0)
    private val reference = SurveyTerrainTakeoffReference(
        point = referencePoint,
        source = SurveyTerrainTakeoffReferenceSource.HOME_LOCATION,
        capturedAtEpochMillis = 1_000L,
    )
    private val terrain = terrain(100.0)

    @Test
    fun `matching live Home and terrain source pass the preflight reference check`() {
        val report = SurveyTerrainTakeoffReferencePolicy.verify(
            plan = plan(reference),
            currentHome = GeoPoint(31.00001, 121.00001),
            terrain = terrain,
        )

        assertTrue(report.valid)
    }

    @Test
    fun `legacy mission without persisted reference fails closed`() {
        val report = SurveyTerrainTakeoffReferencePolicy.verify(
            plan = plan(null),
            currentHome = referencePoint,
            terrain = terrain,
        )

        assertFalse(report.valid)
        assertTrue(report.reason.orEmpty().contains("missing a takeoff reference"))
    }

    @Test
    fun `moved Home or changed terrain datum fails closed`() {
        val moved = SurveyTerrainTakeoffReferencePolicy.verify(
            plan = plan(reference),
            currentHome = GeoPoint(31.001, 121.0),
            terrain = terrain,
        )
        val changedDatum = SurveyTerrainTakeoffReferencePolicy.verify(
            plan = plan(reference),
            currentHome = referencePoint,
            terrain = terrain(104.0),
        )

        assertFalse(moved.valid)
        assertFalse(changedDatum.valid)
    }

    private fun plan(reference: SurveyTerrainTakeoffReference?) = SurveyTerrainPlan(
        sourceName = "terrain",
        sourceSha256 = "a".repeat(64),
        epsg = 4326,
        targetAglMeters = 50.0,
        takeoffTerrainElevationMeters = 100.0,
        sampleSpacingMeters = 3.0,
        minimumTerrainElevationMeters = 100.0,
        maximumTerrainElevationMeters = 120.0,
        minimumWaypointAltitudeMeters = 50.0,
        maximumWaypointAltitudeMeters = 70.0,
        takeoffReference = reference,
    )

    private fun terrain(elevation: Double) = object : TerrainElevationSource {
        override val info = TerrainRasterInfo(
            "terrain", 2, 2, 4326, null, 1.0, 1.0,
            30.0, 32.0, 120.0, 122.0,
        )

        override fun elevationMeters(latitude: Double, longitude: Double): Double = elevation
    }
}
