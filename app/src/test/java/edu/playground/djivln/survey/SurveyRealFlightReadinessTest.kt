package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyRealFlightReadinessTest {
    private fun mission() = SurveyPlanner.plan(
        "audit",
        listOf(
            GeoPoint(31.0, 121.0), GeoPoint(31.0, 121.0002),
            GeoPoint(31.0002, 121.0002), GeoPoint(31.0002, 121.0),
        ),
    )

    @Test
    fun `good live telemetry cannot replace missing validation evidence`() {
        val report = SurveyRealFlightReadiness.evaluate(
            mission(), goodTelemetry(),
            SurveyRealFlightEvidence(false, false, false, false, false),
        )

        assertFalse(report.readyForReview)
        assertTrue(report.blocks.contains(SurveyRealFlightBlock.SIMULATOR_REGRESSION_REQUIRED))
        assertTrue(report.blocks.contains(SurveyRealFlightBlock.OPERATING_AREA_REVIEW_REQUIRED))
    }

    @Test
    fun `all evidence only marks ready for review not flight authorization`() {
        val report = SurveyRealFlightReadiness.evaluate(
            mission(), goodTelemetry(),
            SurveyRealFlightEvidence(true, true, true, true, true),
        )

        assertTrue(report.readyForReview)
        assertTrue(report.blocks.isEmpty())
    }

    @Test
    fun `live preflight rejects simulator weak links and unsafe flight limits`() {
        val report = SurveyRealFlightReadiness.evaluate(
            mission(), goodTelemetry().copy(
                simulatorActive = true,
                rcSignalPercent = 20,
                goHomeHeightMeters = 10,
                maxFlightRadiusEnabled = false,
            ),
            SurveyRealFlightEvidence(true, true, true, true, true),
        )

        assertTrue(report.blocks.contains(SurveyRealFlightBlock.SIMULATOR_MUST_BE_OFF))
        assertTrue(report.blocks.contains(SurveyRealFlightBlock.RC_SIGNAL_WEAK))
        assertTrue(report.blocks.contains(SurveyRealFlightBlock.GO_HOME_HEIGHT_BELOW_MISSION))
        assertFalse(report.blocks.contains(SurveyRealFlightBlock.MAX_FLIGHT_RADIUS_REQUIRED))
    }

    private fun goodTelemetry() = SurveyRealFlightTelemetry(
        connected = true,
        flightStateFresh = true,
        flying = false,
        simulatorActive = false,
        batteryPercent = 90,
        rcBatteryPercent = 80,
        rcSignalPercent = 100,
        satelliteCount = 20,
        gpsSignalUsable = true,
        homeLocationValid = true,
        latitude = 31.0,
        longitude = 121.0,
        goHomeHeightMeters = 60,
        maxFlightHeightMeters = 120,
        maxFlightRadiusMeters = 500,
        maxFlightRadiusEnabled = true,
    )
}
