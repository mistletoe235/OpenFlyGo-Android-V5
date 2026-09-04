package edu.playground.djivln.ui

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHeadingSourceTest {
    @Test
    fun normalizesHeading() {
        assertEquals(350.0, PhoneHeadingSource.normalizeDegrees(-10.0), 0.0)
        assertEquals(10.0, PhoneHeadingSource.normalizeDegrees(370.0), 0.0)
    }

    @Test
    fun smoothingUsesShortestPathAcrossNorth() {
        val smoothed = PhoneHeadingSource.smoothHeading(359.0, 1.0)

        assertTrue(smoothed > 359.0 || smoothed < 1.0)
    }

    @Test
    fun unreliableHeadingIsRejected() {
        val heading = PhoneHeadingSource.Heading(90.0, SensorManager.SENSOR_STATUS_UNRELIABLE, 10L)

        assertTrue(!PhoneHeadingSource.isUsable(heading, 20L))
    }

    @Test
    fun recentUnreliableHeadingIsStillDisplayableOnMap() {
        val heading = PhoneHeadingSource.Heading(90.0, SensorManager.SENSOR_STATUS_UNRELIABLE, 10L)

        assertTrue(PhoneHeadingSource.isDisplayable(heading, 20L))
    }

    @Test
    fun staleHeadingIsRejected() {
        val heading = PhoneHeadingSource.Heading(90.0, SensorManager.SENSOR_STATUS_ACCURACY_HIGH, 10L)

        assertTrue(!PhoneHeadingSource.isUsable(heading, 10L + PhoneHeadingSource.MAX_HEADING_AGE_NANOS + 1L))
        assertTrue(!PhoneHeadingSource.isDisplayable(heading, 10L + PhoneHeadingSource.MAX_HEADING_AGE_NANOS + 1L))
    }

    @Test
    fun recentReliableHeadingIsUsable() {
        val heading = PhoneHeadingSource.Heading(90.0, SensorManager.SENSOR_STATUS_ACCURACY_LOW, 10L)

        assertTrue(PhoneHeadingSource.isUsable(heading, 20L))
    }
}
