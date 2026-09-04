package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class SurveyTerrainPlannerTest {
    @Test
    fun `DSM creates intermediate height controls without restarting capture`() {
        val roi = listOf(
            GeoPoint(31.0, 121.0),
            GeoPoint(31.0, 121.001),
            GeoPoint(31.0005, 121.001),
            GeoPoint(31.0005, 121.0),
        )
        val mission = SurveyPlanner.plan(
            "terrain", roi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0, speedMetersPerSecond = 2.0),
            takeoffPoint = roi.first(),
        )
        val terrain = object : TerrainElevationSource {
            override val info = TerrainRasterInfo(
                "buildings-dsm.tif", 100, 100, 4326, null, 1.0, 1.0,
                30.9, 31.1, 120.9, 121.1,
            )
            override fun elevationMeters(latitude: Double, longitude: Double): Double =
                if (longitude in 121.00040..121.00060 && latitude in 31.0..31.0005) 120.0 else 100.0
        }

        val takeoffReference = SurveyTerrainTakeoffReference(
            roi.first(),
            SurveyTerrainTakeoffReferenceSource.HOME_LOCATION,
            1_000L,
        )
        val result = SurveyTerrainPlanner.apply(mission, terrain, takeoffReference, "a".repeat(64))

        assertTrue(result.mission.waypoints.size > mission.waypoints.size)
        assertTrue(result.mission.surveyPasses().all { pass ->
            pass.start.captureAction == CaptureAction.START_DISTANCE_INTERVAL &&
                pass.end.captureAction == CaptureAction.STOP_DISTANCE_INTERVAL &&
                pass.waypoints.drop(1).dropLast(1).all { it.captureAction == CaptureAction.NONE }
        })
        assertTrue(result.mission.waypoints.map { it.point.altitudeMeters }.distinct().size > 1)
        assertTrue(result.mission.waypoints.all {
            it.point.altitudeMeters + 1e-6 >=
                terrain.elevationMeters(it.point.latitude, it.point.longitude) - 100.0 + 50.0
        })
        assertTrue(result.safety.maximumRequiredVerticalSpeedMetersPerSecond <= 0.45 + 1e-6)
        assertFalse(result.mission.terrainPlan!!.realFlightVerified)
        assertEquals(takeoffReference, result.mission.terrainPlan!!.takeoffReference)
        assertEquals(SurveyTerrainSourceKind.SURFACE_DSM, result.mission.terrainPlan!!.sourceKind)
        val decoded = SurveyMissionJson.decode(SurveyMissionJson.encode(result.mission))
        assertTrue(decoded.terrainPlan != null)
        assertEquals(takeoffReference, decoded.terrainPlan!!.takeoffReference)
        assertEquals(SurveyTerrainSourceKind.SURFACE_DSM, decoded.terrainPlan!!.sourceKind)
        val bareEarthMission = result.mission.copy(
            terrainPlan = result.mission.terrainPlan!!.copy(
                sourceKind = SurveyTerrainSourceKind.BARE_EARTH,
                bareEarthBaseSha256 = "a".repeat(64),
            ),
        )
        val bareEarthDecoded = SurveyMissionJson.decode(SurveyMissionJson.encode(bareEarthMission))
        assertEquals(SurveyTerrainSourceKind.BARE_EARTH, bareEarthDecoded.terrainPlan!!.sourceKind)
        assertEquals("a".repeat(64), bareEarthDecoded.terrainPlan!!.bareEarthBaseSha256)
        val legacyJson = JSONObject(SurveyMissionJson.encode(bareEarthMission))
            .put("schema_version", 10)
        val legacyDecoded = SurveyMissionJson.decode(legacyJson.toString())
        assertEquals(SurveyTerrainSourceKind.SURFACE_DSM, legacyDecoded.terrainPlan!!.sourceKind)
        assertNull(legacyDecoded.terrainPlan!!.bareEarthBaseSha256)
        assertNull(legacyDecoded.terrainPlan!!.takeoffReference)
        assertTrue(decoded.waypoints.any { it.kind == SurveyWaypointKind.TRANSIT })

        val realGate = SurveySimulatorGate.evaluate(
            decoded,
            SurveyExecutionTelemetry(
                connected = true, simulatorActive = false, simulatorFlying = false,
                virtualStickEnabled = false, sticksActive = false,
                latitude = roi.first().latitude, longitude = roi.first().longitude,
                altitudeMeters = 10.0, updatedAtEpochMillis = 1_000L,
                aircraftFlying = true, batteryPercent = 100, rcBatteryPercent = 100,
                rcSignalPercent = 100, satelliteCount = 20, gpsSignalUsable = true,
                homeLocationValid = true, homeLatitude = roi.first().latitude,
                homeLongitude = roi.first().longitude, goHomeHeightMeters = 120,
                maxFlightHeightMeters = 120, maxFlightRadiusMeters = 10_000,
                maxFlightRadiusEnabled = true,
            ),
            nowEpochMillis = 1_000L,
            requireVirtualStick = false,
            environment = SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
        )
        assertTrue(SurveyExecutionBlock.TERRAIN_REAL_FLIGHT_NOT_VERIFIED in realGate.blocks)
    }

    @Test
    fun `public building DSM produces a safe preplanned height profile`() {
        val sample = File("../testdata/terrain/ea-london-dsm-1m.tif")
        val terrain = sample.inputStream().use { GeoTiffTerrain.read(it, sample.name) }
        val info = terrain.info
        fun latitude(fraction: Double) =
            info.minimumLatitude + (info.maximumLatitude - info.minimumLatitude) * fraction
        fun longitude(fraction: Double) =
            info.minimumLongitude + (info.maximumLongitude - info.minimumLongitude) * fraction
        val roi = listOf(
            GeoPoint(latitude(0.25), longitude(0.25)),
            GeoPoint(latitude(0.25), longitude(0.75)),
            GeoPoint(latitude(0.75), longitude(0.75)),
            GeoPoint(latitude(0.75), longitude(0.25)),
        )
        val mission = SurveyPlanner.plan(
            "ea-london-real-dsm",
            roi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 50.0,
                speedMetersPerSecond = 2.0,
            ),
            takeoffPoint = roi.first(),
        )

        val result = SurveyTerrainPlanner.apply(
            mission,
            terrain,
            SurveyTerrainTakeoffReference(
                roi.first(),
                SurveyTerrainTakeoffReferenceSource.HOME_LOCATION,
                1_000L,
            ),
            "29330e7b7b1c4cef8b7bca39ec0734ecbc1c94bd1b9bb638317e1e642ef1aa3c",
        )

        assertTrue(result.mission.terrainPlan != null)
        assertFalse(result.mission.terrainPlan!!.realFlightVerified)
        assertTrue(result.safety.maximumRequiredVerticalSpeedMetersPerSecond <= 0.45 + 1e-6)
        assertTrue(result.mission.waypoints.size > mission.waypoints.size)
        assertTrue(result.mission.waypoints.all { waypoint ->
            waypoint.point.latitude in info.minimumLatitude..info.maximumLatitude &&
                waypoint.point.longitude in info.minimumLongitude..info.maximumLongitude
        })
    }
}
