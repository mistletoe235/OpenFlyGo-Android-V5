package edu.playground.djivln.adapter.dji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DjiTelemetryBatteryPolicyTest {
    @Test
    fun `aircraft prefers DJI aggregate and falls back to lower pack`() {
        assertEquals(67, DjiTelemetryBatteryPolicy.aircraftPercent(67, 20, 90))
        assertEquals(20, DjiTelemetryBatteryPolicy.aircraftPercent(null, 20, 90))
        assertEquals(90, DjiTelemetryBatteryPolicy.aircraftPercent(null, null, 90))
        assertNull(DjiTelemetryBatteryPolicy.aircraftPercent(null, null, null))
    }

    @Test
    fun `RC Plus remains available while either enabled battery has charge`() {
        assertEquals(82, DjiTelemetryBatteryPolicy.remoteControllerAvailablePercent(26, 82))
        assertEquals(26, DjiTelemetryBatteryPolicy.remoteControllerAvailablePercent(26, null))
        assertEquals(82, DjiTelemetryBatteryPolicy.remoteControllerAvailablePercent(null, 82))
        assertNull(DjiTelemetryBatteryPolicy.remoteControllerAvailablePercent(null, null))
    }

    @Test
    fun `connected RC keeps last valid battery across transient empty reports`() {
        assertEquals(11, DjiTelemetryBatteryPolicy.retainConnectedRcPercent(11, null, null))
        assertEquals(11, DjiTelemetryBatteryPolicy.retainConnectedRcPercent(11, 0, false))
        assertEquals(12, DjiTelemetryBatteryPolicy.retainConnectedRcPercent(11, 12, true))
        assertNull(DjiTelemetryBatteryPolicy.retainConnectedRcPercent(null, null, null))
    }
}
