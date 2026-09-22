package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraOrientationResolverTest {
    @Test
    fun composesAircraftHeadingAndRelativeGimbalYawAcrossWraparound() {
        val result = CameraOrientationResolver.resolve(350.0, 1.0, -45.0, 12.0, 20.0)

        assertEquals(12.0, result.yawDegrees!!, 0.0)
        assertEquals(CameraOrientationResolver.SOURCE_ABSOLUTE_GIMBAL_CROSS_CHECKED, result.yawSource)
        assertEquals(2.0, result.yawConsistencyErrorDegrees!!, 0.0)
        assertEquals(-45.0, result.pitchDegrees!!, 0.0)
        assertEquals(1.0, result.rollDegrees!!, 0.0)
    }

    @Test
    fun usesComposedYawWhenAbsoluteGimbalYawConflicts() {
        val result = CameraOrientationResolver.resolve(350.0, 0.0, -45.0, 100.0, 20.0)

        assertEquals(10.0, result.yawDegrees!!, 0.0)
        assertEquals(CameraOrientationResolver.SOURCE_HEADING_PLUS_RELATIVE_GIMBAL_CONFLICT, result.yawSource)
        assertEquals(90.0, result.yawConsistencyErrorDegrees!!, 0.0)
    }

    @Test
    fun usesAbsoluteGimbalYawWhenRelativeYawIsUnavailable() {
        val result = CameraOrientationResolver.resolve(45.0, 0.0, -90.0, -30.0, null)

        assertEquals(330.0, result.yawDegrees!!, 0.0)
        assertEquals(CameraOrientationResolver.SOURCE_ABSOLUTE_GIMBAL, result.yawSource)
        assertNull(result.yawConsistencyErrorDegrees)
    }

    @Test
    fun labelsAircraftHeadingAsFallbackInsteadOfPretendingItIsGimbalYaw() {
        val result = CameraOrientationResolver.resolve(725.0, null, -90.0, null, null)

        assertEquals(5.0, result.yawDegrees!!, 0.0)
        assertEquals(CameraOrientationResolver.SOURCE_AIRCRAFT_HEADING_FALLBACK, result.yawSource)
    }
}
