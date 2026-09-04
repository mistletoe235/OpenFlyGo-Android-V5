package edu.playground.djivln.qualification

import edu.playground.djivln.domain.telemetry.AircraftSnapshot

enum class HardwareGateLevel { OFFLINE, CONNECTED, SIMULATOR, PROPS_REMOVED, AIRBORNE }

data class DeviceQualificationGate(
    val level: HardwareGateLevel,
    val controlActionsAllowed: Boolean,
)

object DeviceQualificationPolicy {
    fun evaluate(
        snapshot: AircraftSnapshot,
        propsRemovedConfirmed: Boolean
    ): DeviceQualificationGate = when {
        !snapshot.connected -> DeviceQualificationGate(
            HardwareGateLevel.OFFLINE,
            controlActionsAllowed = false,
        )

        snapshot.isFlying || snapshot.motorsOn -> DeviceQualificationGate(
            HardwareGateLevel.AIRBORNE,
            controlActionsAllowed = false,
        )

        snapshot.simulatorActive -> DeviceQualificationGate(
            HardwareGateLevel.SIMULATOR,
            controlActionsAllowed = true,
        )

        propsRemovedConfirmed -> DeviceQualificationGate(
            HardwareGateLevel.PROPS_REMOVED,
            controlActionsAllowed = true,
        )

        else -> DeviceQualificationGate(
            HardwareGateLevel.CONNECTED,
            controlActionsAllowed = false,
        )
    }
}
