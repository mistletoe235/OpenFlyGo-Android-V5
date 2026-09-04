package edu.playground.djivln.ui

import edu.playground.djivln.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLocationPolicyTest {
    private val now = 1_000_000L

    @Test
    fun `fresh accurate phone fix is usable`() {
        assertTrue(MapLocationPolicy.isUsable(31.0253, 121.4371, now - 1_000L, 8f, now))
    }

    @Test
    fun `stale inaccurate and missing-accuracy fixes are rejected`() {
        assertFalse(MapLocationPolicy.isUsable(
            31.0253, 121.4371, now - MapLocationPolicy.MAX_AGE_MILLIS - 1L, 8f, now,
        ))
        assertFalse(MapLocationPolicy.isUsable(
            31.0253, 121.4371, now, MapLocationPolicy.MAX_ACCURACY_METERS + 1f, now,
        ))
        assertFalse(MapLocationPolicy.isUsable(31.0253, 121.4371, now, null, now))
    }

    @Test
    fun `invalid and zero coordinates are rejected`() {
        assertFalse(MapLocationPolicy.isUsable(Double.NaN, 121.4371, now, 8f, now))
        assertFalse(MapLocationPolicy.isUsable(91.0, 121.4371, now, 8f, now))
        assertFalse(MapLocationPolicy.isUsable(0.0, 0.0, now, 8f, now))
    }

    @Test
    fun `expanded unavailable status identifies every location source`() {
        val result = MapLocationPolicy.unavailableStatus(
            aircraftConnected = true,
            aircraftSatellites = 0,
            remoteControllerGpsValid = false,
            phoneLocationPermissionGranted = true,
            phoneLocationListening = true,
            compact = false,
        )
        assertEquals(R.string.map_no_location_detail, result.resourceId)
        assertEquals(3, result.arguments.size)
    }

    @Test
    fun `thumbnail unavailable status stays compact`() {
        assertEquals(
            R.string.no_live_location,
            MapLocationPolicy.unavailableStatus(
                aircraftConnected = true,
                aircraftSatellites = 0,
                remoteControllerGpsValid = null,
                phoneLocationPermissionGranted = false,
                phoneLocationListening = false,
                compact = true,
            ).resourceId,
        )
    }
}
