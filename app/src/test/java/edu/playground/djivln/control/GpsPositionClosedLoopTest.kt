package edu.playground.djivln.control

import edu.playground.djivln.control.ControlVelocityCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class GpsPositionClosedLoopTest {
    private val now = 10_000L

    private fun pose(
        heading: Double = 0.0,
        northMeters: Double = 0.0,
        eastMeters: Double = 0.0,
        rtk: Boolean = true,
        timestamp: Long = now,
        altitudeMeters: Double = 10.0,
        horizontalSpeed: Double? = null,
        velocityTimestamp: Long? = null,
        simulated: Boolean = false,
        verticalSpeedUp: Double? = null,
        verticalTimestamp: Long? = null,
        downwardHeight: Double? = null,
    ): PositionControlPose {
        val latitude = 31.2304 + Math.toDegrees(northMeters / EARTH_RADIUS)
        val longitude = 121.4737 + Math.toDegrees(
            eastMeters / (EARTH_RADIUS * cos(Math.toRadians(31.2304))),
        )
        return PositionControlPose(
            latitude, longitude, altitudeMeters, heading, timestamp, rtk,
            if (rtk) "RTK_FIXED" else "GPS",
            simulated = simulated,
            horizontalSpeedMetersPerSecond = horizontalSpeed,
            velocityTimestampMs = velocityTimestamp,
            verticalSpeedUpMetersPerSecond = verticalSpeedUp,
            verticalVelocityTimestampMs = verticalTimestamp,
            downwardHeightMeters = downwardHeight,
        )
    }

    private fun target(forward: Double, right: Double = 0.0, up: Double = 0.0) = ControlVelocityCommand(
        vxMetersPerSecond = forward,
        vyMetersPerSecond = right,
        vzMetersPerSecond = 0.0,
        yawRateDegreesPerSecond = 0.0,
        confidence = 1.0,
        reason = "test",
        targetForwardMeters = forward,
        targetRightMeters = right,
        targetUpMeters = up,
    )

    @Test fun northHeadingForwardClosesAtNorthTarget() {
        val controller = GpsPositionClosedLoop()
        assertEquals(null, controller.start(target(1.0), pose(heading = 0.0), now))
        val initial = controller.step(pose(heading = 0.0), now)
        assertTrue(initial.command.vxMetersPerSecond > 0.9)
        assertEquals(0.0, initial.command.vyMetersPerSecond, 0.01)
        assertTrue(controller.step(pose(heading = 0.0, northMeters = 1.0), now + 200).successful)
    }

    @Test fun eastHeadingForwardClosesAtEastTarget() {
        val controller = GpsPositionClosedLoop()
        assertEquals(null, controller.start(target(1.0), pose(heading = 90.0), now))
        val initial = controller.step(pose(heading = 90.0), now)
        assertTrue(initial.command.vxMetersPerSecond > 0.9)
        assertEquals(0.0, initial.command.vyMetersPerSecond, 0.01)
        assertTrue(controller.step(pose(heading = 90.0, eastMeters = 1.0), now + 200).successful)
    }

    @Test fun bodyRightUsesCurrentHeadingTransform() {
        val northFacing = GpsPositionClosedLoop()
        northFacing.start(target(0.0, right = 1.0), pose(heading = 0.0), now)
        assertTrue(northFacing.step(pose(heading = 0.0), now).command.vyMetersPerSecond > 0.9)
        assertTrue(northFacing.step(pose(heading = 0.0, eastMeters = 1.0), now + 200).successful)

        val eastFacing = GpsPositionClosedLoop()
        eastFacing.start(target(0.0, right = 1.0), pose(heading = 90.0), now)
        assertTrue(eastFacing.step(pose(heading = 90.0), now).command.vyMetersPerSecond > 0.9)
        assertTrue(eastFacing.step(pose(heading = 90.0, northMeters = -1.0), now + 200).successful)
    }

    @Test fun slowsNearTargetAndAbortsWhenRtkFixIsLost() {
        val controller = GpsPositionClosedLoop()
        controller.start(target(2.0), pose(), now)
        assertEquals(2.0, controller.step(pose(), now).command.vxMetersPerSecond, 0.02)
        val near = controller.step(pose(northMeters = 1.5), now + 200)
        assertTrue(near.command.vxMetersPerSecond in 0.4..0.6)
        val lost = controller.step(pose(northMeters = 1.5, rtk = false), now + 300)
        assertTrue(lost.terminal)
        assertFalse(lost.successful)
        assertEquals(0.0, lost.command.vxMetersPerSecond, 0.0)
    }

    @Test fun zeroModelZKeepsVerticalStickCenteredDespiteAltitudeDrift() {
        val controller = GpsPositionClosedLoop()
        controller.start(target(1.0), pose(altitudeMeters = 10.0), now)

        assertEquals(0.0, controller.step(pose(altitudeMeters = 10.0), now).command.vzMetersPerSecond, 0.0)
        val drifted = controller.step(
            pose(northMeters = 0.2, altitudeMeters = 9.4, timestamp = now + 200),
            now + 200,
        )
        assertFalse(drifted.terminal)
        assertEquals(0.0, drifted.command.vzMetersPerSecond, 0.0)
    }

    @Test fun configuredSpeedAppliesToBothRtkAndGpsClosure() {
        val rtkController = GpsPositionClosedLoop().apply { setMaximumHorizontalSpeed(1.4) }
        rtkController.start(target(5.0), pose(rtk = true), now)
        assertEquals(1.4, rtkController.step(pose(rtk = true), now).command.vxMetersPerSecond, 0.01)

        val gpsController = GpsPositionClosedLoop().apply { setMaximumHorizontalSpeed(2.0) }
        gpsController.start(target(5.0), pose(rtk = false), now)
        assertEquals(2.0, gpsController.step(pose(rtk = false), now).command.vxMetersPerSecond, 0.01)
    }

    @Test fun velocityEstimateIntegratesMeasuredSpeedAlongAppliedBodyCommand() {
        val controller = GpsPositionClosedLoop().apply {
            setMode(PositionClosureMode.VELOCITY_ESTIMATE)
        }
        val start = pose(rtk = false, horizontalSpeed = 0.0, velocityTimestamp = now)
        assertEquals(null, controller.start(target(1.0), start, now))
        val initial = controller.step(start, now)
        controller.recordAppliedCommand(initial.command)

        var step = initial
        for (index in 1..4) {
            val timestamp = now + index * 250L
            step = controller.step(
                pose(
                    rtk = false,
                    timestamp = timestamp,
                    horizontalSpeed = 1.0,
                    velocityTimestamp = timestamp,
                ),
                timestamp,
            )
            if (step.terminal) break
            controller.recordAppliedCommand(step.command)
        }

        assertTrue(step.terminal)
        assertTrue(step.successful)
    }

    @Test fun velocityEstimateRequiresExplicitModeAndEnforcesStrictDistance() {
        val noGpsPose = pose(
            rtk = false,
            horizontalSpeed = 0.0,
            velocityTimestamp = now,
        ).copy(latitude = Double.NaN, longitude = Double.NaN)
        val gpsController = GpsPositionClosedLoop()
        assertTrue(gpsController.start(target(1.0), noGpsPose, now)?.contains("GPS/RTK") == true)

        val estimateController = GpsPositionClosedLoop().apply {
            setMode(PositionClosureMode.VELOCITY_ESTIMATE)
        }
        assertEquals(null, estimateController.start(target(1.0), noGpsPose, now))
        estimateController.cancel()
        assertTrue(estimateController.start(target(2.1), noGpsPose, now)?.contains("2.0m") == true)
    }

    @Test fun readinessChecksRequestedModeWithoutMutatingActiveMode() {
        val controller = GpsPositionClosedLoop()
        val velocityOnlyPose = pose(
            rtk = false,
            horizontalSpeed = 0.0,
            velocityTimestamp = now,
        ).copy(latitude = Double.NaN, longitude = Double.NaN)

        assertEquals(
            null,
            controller.readinessIssue(velocityOnlyPose, now, PositionClosureMode.VELOCITY_ESTIMATE),
        )
        assertTrue(
            controller.readinessIssue(velocityOnlyPose, now, PositionClosureMode.GPS_RTK)
                ?.contains("GPS/RTK") == true,
        )
        assertEquals(PositionClosureMode.GPS_RTK, controller.currentMode())
    }

    @Test fun freshAttitudeCannotMaskStaleGpsOrVelocityTimestamps() {
        val controller = GpsPositionClosedLoop(maxPoseAgeMs = 750L)
        val freshAttitudeStaleSensors = pose(
            timestamp = now,
            horizontalSpeed = 0.2,
            velocityTimestamp = now - 1_000L,
        ).copy(positionTimestampMs = now - 1_000L)

        assertTrue(
            controller.readinessIssue(freshAttitudeStaleSensors, now, PositionClosureMode.GPS_RTK)
                ?.contains("GPS/RTK") == true,
        )
        assertTrue(
            controller.readinessIssue(freshAttitudeStaleSensors, now, PositionClosureMode.VELOCITY_ESTIMATE)
                ?.contains("velocity integration") == true,
        )
    }

    @Test fun velocityEstimateAbortsOnStaleVelocityTelemetry() {
        val controller = GpsPositionClosedLoop().apply {
            setMode(PositionClosureMode.VELOCITY_ESTIMATE)
        }
        val start = pose(rtk = false, horizontalSpeed = 0.0, velocityTimestamp = now)
        controller.start(target(1.0), start, now)

        val stale = controller.step(
            pose(
                rtk = false,
                timestamp = now + 800,
                horizontalSpeed = 0.2,
                velocityTimestamp = now,
            ),
            now + 800,
        )
        assertTrue(stale.terminal)
        assertFalse(stale.successful)
        assertTrue(stale.reason.contains("velocity integration"))
    }

    @Test fun relativeVerticalTargetIntegratesMeasuredUpVelocityThenCentersStick() {
        val controller = GpsPositionClosedLoop()
        val start = pose(verticalSpeedUp = 0.0, verticalTimestamp = now)
        assertEquals(null, controller.start(target(0.0, up = 0.4), start, now))
        val initial = controller.step(start, now)
        assertEquals(0.2, initial.command.vzMetersPerSecond, 0.001)

        var step = initial
        for (index in 1..8) {
            val timestamp = now + index * 200L
            step = controller.step(
                pose(
                    timestamp = timestamp,
                    verticalSpeedUp = 0.2,
                    verticalTimestamp = timestamp,
                ),
                timestamp,
            )
        }
        assertFalse(step.terminal)
        assertEquals(0.0, step.command.vzMetersPerSecond, 0.001)

        val settledAt = now + 1_800L
        step = controller.step(
            pose(timestamp = settledAt, verticalSpeedUp = 0.0, verticalTimestamp = settledAt),
            settledAt,
        )
        assertTrue(step.terminal)
        assertTrue(step.successful)
    }

    @Test fun barometerDriftDoesNotCountAsVerticalProgress() {
        val controller = GpsPositionClosedLoop()
        val start = pose(altitudeMeters = 10.0, verticalSpeedUp = 0.0, verticalTimestamp = now)
        controller.start(target(0.0, up = 0.4), start, now)

        val later = now + 200L
        val step = controller.step(
            pose(
                timestamp = later,
                altitudeMeters = 8.5,
                verticalSpeedUp = 0.0,
                verticalTimestamp = later,
            ),
            later,
        )
        assertEquals(0.4, step.verticalErrorMeters, 0.001)
        assertEquals(0.2, step.command.vzMetersPerSecond, 0.001)
    }

    @Test fun verticalTargetAndLowAltitudeDescentAreRejected() {
        val controller = GpsPositionClosedLoop()
        val safePose = pose(
            altitudeMeters = 10.0,
            verticalSpeedUp = 0.0,
            verticalTimestamp = now,
            downwardHeight = 1.0,
        )
        assertTrue(controller.start(target(0.0, up = 0.6), safePose, now)?.contains("0.5") == true)
        assertTrue(controller.start(target(0.0, up = -0.3), safePose, now)?.contains("0.8") == true)
    }

    @Test fun activeVerticalTargetAbortsWhenVerticalVelocityBecomesStale() {
        val controller = GpsPositionClosedLoop()
        val start = pose(verticalSpeedUp = 0.0, verticalTimestamp = now)
        controller.start(target(0.0, up = 0.3), start, now)

        val stale = controller.step(
            pose(
                timestamp = now + 800L,
                verticalSpeedUp = 0.1,
                verticalTimestamp = now,
            ),
            now + 800L,
        )
        assertTrue(stale.terminal)
        assertFalse(stale.successful)
        assertTrue(stale.reason.contains("vertical velocity"))
    }

    private companion object {
        const val EARTH_RADIUS = 6_378_137.0
    }
}
