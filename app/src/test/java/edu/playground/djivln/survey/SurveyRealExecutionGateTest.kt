package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyRealExecutionGateTest {
    private val now = 1_700_000_000_000L

    private fun mission(takeoffMode: SurveyTakeoffMode = SurveyTakeoffMode.MANUAL) =
        SurveyPlanner.plan(
            "real-gate",
            listOf(
                GeoPoint(31.0, 121.0), GeoPoint(31.0, 121.0001),
                GeoPoint(31.0001, 121.0001), GeoPoint(31.0001, 121.0),
            ),
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                safeTakeoffAltitudeMeters = 20.0,
                speedMetersPerSecond = 1.0,
                takeoffMode = takeoffMode,
            ),
        )

    private fun telemetry() = SurveyExecutionTelemetry(
        connected = true,
        simulatorActive = false,
        simulatorFlying = false,
        virtualStickEnabled = false,
        sticksActive = false,
        latitude = 31.0,
        longitude = 121.0,
        altitudeMeters = 10.0,
        updatedAtEpochMillis = now,
        aircraftFlying = true,
        batteryPercent = 80,
        rcBatteryPercent = 80,
        rcSignalPercent = 90,
        satelliteCount = 18,
        gpsSignalUsable = true,
        homeLocationValid = true,
        goHomeHeightMeters = 60,
        maxFlightHeightMeters = 120,
        maxFlightRadiusMeters = 500,
        maxFlightRadiusEnabled = true,
        horizontalSpeedMetersPerSecond = 0.1,
        verticalSpeedMetersPerSecond = 0.0,
    )

    private fun evaluate(
        mission: SurveyMission = mission(),
        telemetry: SurveyExecutionTelemetry = telemetry(),
        requireVirtualStick: Boolean = false,
    ) = SurveySimulatorGate.evaluate(
        mission, telemetry, now, requireVirtualStick,
        environment = SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
    )

    @Test
    fun `real aircraft may arm only after manual takeoff and stable hover`() {
        val allowed = evaluate()
        assertTrue(allowed.blocks.toString(), allowed.allowed)
        assertTrue(evaluate(telemetry = telemetry().copy(aircraftFlying = false)).blocks
            .contains(SurveyExecutionBlock.REAL_AIRCRAFT_NOT_FLYING))
        assertTrue(evaluate(telemetry = telemetry().copy(horizontalSpeedMetersPerSecond = 1.2)).blocks
            .contains(SurveyExecutionBlock.REAL_AIRCRAFT_NOT_STABLY_HOVERING))
        assertTrue(evaluate(mission = mission(SurveyTakeoffMode.AUTO_SIMULATOR_ONLY)).blocks
            .contains(SurveyExecutionBlock.REAL_REQUIRES_MANUAL_TAKEOFF))
    }

    @Test
    fun `real aircraft requires simulator off and all live flight safety inputs`() {
        val result = evaluate(telemetry = telemetry().copy(
            simulatorActive = true,
            rcSignalPercent = 39,
            satelliteCount = 11,
            gpsSignalUsable = false,
            homeLocationValid = false,
        ))

        assertFalse(result.allowed)
        assertTrue(result.blocks.contains(SurveyExecutionBlock.SIMULATOR_MUST_BE_OFF))
        assertTrue(result.blocks.contains(SurveyExecutionBlock.RC_SIGNAL_WEAK))
        assertTrue(result.blocks.contains(SurveyExecutionBlock.GPS_SATELLITES_LOW))
        assertTrue(result.blocks.contains(SurveyExecutionBlock.GPS_SIGNAL_WEAK))
        assertTrue(result.blocks.contains(SurveyExecutionBlock.HOME_LOCATION_REQUIRED))
    }

    @Test
    fun `real running gate requires virtual stick and keeps safety limits active`() {
        val withoutVs = evaluate(requireVirtualStick = true)
        assertFalse(withoutVs.allowed)
        assertTrue(withoutVs.blocks.contains(SurveyExecutionBlock.VIRTUAL_STICK_REQUIRED))

        val withVs = evaluate(telemetry = telemetry().copy(virtualStickEnabled = true),
            requireVirtualStick = true)
        assertTrue(withVs.blocks.toString(), withVs.allowed)

        val unsafeLimits = evaluate(telemetry = telemetry().copy(
            goHomeHeightMeters = 10,
            maxFlightHeightMeters = 20,
            maxFlightRadiusEnabled = false,
        ))
        assertTrue(unsafeLimits.blocks.contains(SurveyExecutionBlock.GO_HOME_HEIGHT_UNSAFE))
        assertTrue(unsafeLimits.blocks.contains(SurveyExecutionBlock.MAX_FLIGHT_HEIGHT_TOO_LOW))
        assertFalse(unsafeLimits.blocks.contains(SurveyExecutionBlock.MAX_FLIGHT_RADIUS_REQUIRED))
    }

    @Test
    fun `startup and running allow long transit to first waypoint`() {
        val farFromStart = telemetry().copy(
            latitude = 31.001,
            longitude = 121.001,
            virtualStickEnabled = true,
        )
        val startup = SurveySimulatorGate.evaluate(
            mission(), farFromStart, now, requireVirtualStick = true,
            environment = SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
        )
        val runtime = SurveySimulatorGate.evaluate(
            mission(), farFromStart, now, requireVirtualStick = true,
            environment = SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
            checkPreflightReadiness = false,
        )

        assertTrue(startup.blocks.toString(), startup.allowed)
        assertTrue(runtime.blocks.toString(), runtime.allowed)
    }

    @Test
    fun `runtime ignores preflight-only readiness fluctuations`() {
        val inFlightFluctuation = telemetry().copy(
            virtualStickEnabled = true,
            batteryPercent = 20,
            rcBatteryPercent = 20,
            rcSignalPercent = 10,
            satelliteCount = 8,
            gpsSignalUsable = false,
            homeLocationValid = false,
            goHomeHeightMeters = 10,
            maxFlightHeightMeters = 10,
            maxFlightRadiusEnabled = false,
        )
        val runtime = SurveySimulatorGate.evaluate(
            mission(), inFlightFluctuation, now, requireVirtualStick = true,
            environment = SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
            checkPreflightReadiness = false,
        )

        assertTrue(runtime.blocks.toString(), runtime.allowed)
    }

    @Test
    fun `runtime stops when DJI flight controller enters return or landing`() {
        val runtime = SurveySimulatorGate.evaluate(
            mission(), telemetry().copy(virtualStickEnabled = true, goingHome = true),
            now, requireVirtualStick = true,
            environment = SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
            checkPreflightReadiness = false,
        )

        assertTrue(runtime.blocks.contains(SurveyExecutionBlock.FLIGHT_CONTROLLER_FAILSAFE_ACTIVE))
    }
}
