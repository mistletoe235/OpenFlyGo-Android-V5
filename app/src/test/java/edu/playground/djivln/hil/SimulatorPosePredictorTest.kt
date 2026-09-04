package edu.playground.djivln.hil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorPosePredictorTest {
    @Test
    fun extrapolatesBetweenRawUpdatesAtOutputCadence() {
        val previous = pose(timeNanos = 1_000_000_000L, east = 0.0, yaw = 0.0)
        val current = pose(timeNanos = 1_025_000_000L, east = 0.25, yaw = 2.5)

        val predicted = SimulatorPosePredictor.predict(previous, current, 1_035_000_000L)

        assertEquals(0.35, predicted.eastMeters, 0.0001)
        assertEquals(3.5, predicted.yawDegrees, 0.0001)
        assertEquals(10.0, predicted.velocityEastMetersPerSecond, 0.0001)
        assertEquals(0.0, predicted.velocityNorthMetersPerSecond, 0.0001)
        assertTrue(predicted.extrapolated)
    }

    @Test
    fun clampsPredictionHorizonAndVelocity() {
        val previous = pose(timeNanos = 1_000_000_000L, east = 0.0, yaw = 0.0)
        val current = pose(timeNanos = 1_010_000_000L, east = 10.0, yaw = 90.0)

        val predicted = SimulatorPosePredictor.predict(previous, current, 2_000_000_000L)

        assertEquals(11.05, predicted.eastMeters, 0.0001)
        assertEquals(102.6, predicted.yawDegrees, 0.0001)
        assertEquals(30.0, predicted.velocityEastMetersPerSecond, 0.0001)
    }

    @Test
    fun holdsLatestPoseWithoutAUsablePreviousFrame() {
        val current = pose(timeNanos = 1_000_000_000L, east = 2.0, yaw = -179.0)

        val predicted = SimulatorPosePredictor.predict(null, current, 1_010_000_000L)

        assertEquals(2.0, predicted.eastMeters, 0.0)
        assertEquals(-179.0, predicted.yawDegrees, 0.0)
        assertEquals(0.0, predicted.velocityEastMetersPerSecond, 0.0)
        assertFalse(predicted.extrapolated)
    }

    @Test
    fun followsShortestYawPathAcrossWraparound() {
        val previous = pose(timeNanos = 1_000_000_000L, east = 0.0, yaw = 179.0)
        val current = pose(timeNanos = 1_025_000_000L, east = 0.0, yaw = -179.0)

        val predicted = SimulatorPosePredictor.predict(previous, current, 1_035_000_000L)

        assertEquals(-178.2, predicted.yawDegrees, 0.0001)
    }

    private fun pose(timeNanos: Long, east: Double, yaw: Double) = SimulatorRawPose(
        elapsedRealtimeNanos = timeNanos,
        originLatitude = 31.0,
        originLongitude = 121.0,
        eastMeters = east,
        northMeters = 0.0,
        upMeters = 10.0,
        rollDegrees = 0.0,
        pitchDegrees = 0.0,
        yawDegrees = yaw,
        motorsOn = true,
        flying = true,
    )
}
