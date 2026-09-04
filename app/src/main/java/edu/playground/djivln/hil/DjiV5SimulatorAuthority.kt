package edu.playground.djivln.hil

import edu.playground.djivln.domain.telemetry.AircraftSnapshot

object DjiV5SimulatorAuthority {
    fun isActive(managerEnabled: Boolean, keyReportedActive: Boolean): Boolean =
        managerEnabled || keyReportedActive

    fun apply(snapshot: AircraftSnapshot, managerEnabled: Boolean): AircraftSnapshot =
        if (managerEnabled && !snapshot.simulatorActive) {
            snapshot.copy(simulatorActive = true)
        } else {
            snapshot
        }
}
