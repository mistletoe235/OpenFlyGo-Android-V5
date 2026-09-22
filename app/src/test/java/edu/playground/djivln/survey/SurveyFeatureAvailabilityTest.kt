package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyFeatureAvailabilityTest {
    private fun mission(): SurveyMission = SurveyRegressionMissionFactory.create(
        center = GeoPoint(31.025, 121.435), fiveDirection = false,
    )

    private fun terrainMission(verified: Boolean = false): SurveyMission = mission().copy(
        terrainPlan = SurveyTerrainPlan(
            sourceName = "fixture", sourceSha256 = "fixture", epsg = 4326,
            targetAglMeters = 30.0, takeoffTerrainElevationMeters = 5.0,
            sampleSpacingMeters = 1.0, minimumTerrainElevationMeters = 5.0,
            maximumTerrainElevationMeters = 12.0, minimumWaypointAltitudeMeters = 30.0,
            maximumWaypointAltitudeMeters = 37.0, realFlightVerified = verified,
        ),
    )

    @Test
    fun normalRoutesRemainSupported() {
        assertFalse(SurveyFeatureAvailability.TERRAIN_FOLLOWING_ENABLED)
        assertTrue(SurveyFeatureAvailability.supportsMission(mission()))
        SurveyFeatureAvailability.requireSupportedMission(mission())
    }

    @Test
    fun importedTerrainCannotBeEnabledByItsVerifiedFlag() {
        for (verified in listOf(false, true)) {
            val imported = SurveyMissionJson.decode(SurveyMissionJson.encode(terrainMission(verified)))
            assertFalse(SurveyFeatureAvailability.supportsMission(imported))
            assertThrows(IllegalArgumentException::class.java) {
                SurveyFeatureAvailability.requireSupportedMission(imported)
            }
        }
    }

    @Test
    fun executionAndResumeGatesRejectTerrainInBothFlightEnvironments() {
        val source = terrainMission(verified = true)
        for (environment in SurveyExecutionEnvironment.values()) {
            for (preflight in listOf(false, true)) {
                val simulator = environment == SurveyExecutionEnvironment.DJI_SIMULATOR
                val result = SurveySimulatorGate.evaluate(
                    source,
                    SurveyExecutionTelemetry(
                        connected = true, simulatorActive = simulator, simulatorFlying = simulator,
                        virtualStickEnabled = true, sticksActive = false,
                        latitude = 31.025, longitude = 121.435, altitudeMeters = 30.0,
                        updatedAtEpochMillis = 1_000L, aircraftFlying = true,
                    ),
                    nowEpochMillis = 1_000L, requireVirtualStick = true,
                    environment = environment, checkPreflightReadiness = preflight,
                )
                assertFalse(result.allowed)
                assertTrue(SurveyExecutionBlock.TERRAIN_FEATURE_DISABLED in result.blocks)
            }
        }
    }
}
