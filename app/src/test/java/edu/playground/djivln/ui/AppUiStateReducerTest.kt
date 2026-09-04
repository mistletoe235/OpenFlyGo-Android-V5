package edu.playground.djivln.ui

import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.R
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateReducerTest {
    @Test
    fun unavailableSdkRendersErrorWithoutPretendingAircraftIsConnected() {
        val result = AppUiStateReducer.fromSdk(
            DjiSdkBootstrap.State(initialized = true, lastError = "native library unavailable")
        )

        assertEquals(R.string.sdk_unavailable, result.sdkLabel.resourceId)
        assertEquals("native library unavailable", result.error)
        assertFalse(result.flight.connected)
    }

    @Test
    fun connectedProductRendersProductIdentity() {
        val result = AppUiStateReducer.fromSdk(
            DjiSdkBootstrap.State(initialized = true, registered = true, connected = true, productId = 42)
        )

        assertEquals(R.string.sdk_connected, result.sdkLabel.resourceId)
        assertEquals(R.string.aircraft_connected_product, result.flight.productLabel.resourceId)
        assertEquals(listOf(42), result.flight.productLabel.arguments)
        assertTrue(result.flight.connected)
        assertNull(result.error)
    }

    @Test
    fun telemetryDiagnosticsExposeNormalizedVelocityAndUnsupportedFields() {
        val result = AppUiStateReducer.fromSdk(
            DjiSdkBootstrap.State(connected = true),
            AircraftSnapshot(
                connected = true,
                velocity = VelocityMetersPerSecond(1.0, 2.0, 3.0),
                unsupportedFields = setOf("agl")
            )
        )

        assertEquals(R.string.telemetry_diagnostics, result.flight.diagnostics.resourceId)
        assertTrue(result.flight.diagnostics.arguments.contains("N 1.00  E 2.00  U 3.00 m/s"))
        assertTrue(result.flight.diagnostics.arguments.contains("agl"))
    }
}
