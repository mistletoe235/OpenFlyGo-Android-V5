package edu.playground.djivln.hil

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiV5SimulatorAuthorityTest {
    @Test
    fun managerStateCoversLaggingSimulatorKey() {
        val effective = DjiV5SimulatorAuthority.apply(
            AircraftSnapshot(simulatorActive = false),
            managerEnabled = true,
        )

        assertTrue(effective.simulatorActive)
    }

    @Test
    fun simulatorKeyCoversLaggingManagerState() {
        assertTrue(DjiV5SimulatorAuthority.isActive(false, true))
    }

    @Test
    fun inactiveStateRemainsInactiveWithoutCopy() {
        val snapshot = AircraftSnapshot(simulatorActive = false)
        val effective = DjiV5SimulatorAuthority.apply(snapshot, managerEnabled = false)

        assertFalse(effective.simulatorActive)
        assertSame(snapshot, effective)
    }
}
