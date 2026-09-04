package edu.playground.djivln.qualification

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQualificationPolicyTest {
    @Test fun offlineNeverAllowsControl() {
        val gate = DeviceQualificationPolicy.evaluate(AircraftSnapshot(), propsRemovedConfirmed = true)
        assertEquals(HardwareGateLevel.OFFLINE, gate.level)
        assertFalse(gate.controlActionsAllowed)
    }

    @Test fun simulatorAllowsGroundedPulseWithoutPropsConfirmation() {
        val gate = DeviceQualificationPolicy.evaluate(
            AircraftSnapshot(connected = true, simulatorActive = true),
            propsRemovedConfirmed = false
        )
        assertEquals(HardwareGateLevel.SIMULATOR, gate.level)
        assertTrue(gate.controlActionsAllowed)
    }

    @Test fun propsRemovedConfirmationAllowsGroundedPulse() {
        val gate = DeviceQualificationPolicy.evaluate(
            AircraftSnapshot(connected = true),
            propsRemovedConfirmed = true
        )
        assertEquals(HardwareGateLevel.PROPS_REMOVED, gate.level)
        assertTrue(gate.controlActionsAllowed)
    }

    @Test fun airborneStateOverridesEveryConfirmation() {
        val gate = DeviceQualificationPolicy.evaluate(
            AircraftSnapshot(connected = true, simulatorActive = true, motorsOn = true, isFlying = true),
            propsRemovedConfirmed = true
        )
        assertEquals(HardwareGateLevel.AIRBORNE, gate.level)
        assertFalse(gate.controlActionsAllowed)
    }
}
