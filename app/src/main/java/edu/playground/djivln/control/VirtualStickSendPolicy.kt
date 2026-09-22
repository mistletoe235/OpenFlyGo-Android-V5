package edu.playground.djivln.control

import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import edu.playground.djivln.domain.flight.FlightControlPortState

internal object VirtualStickSendPolicy {
    fun issue(state: FlightControlPortState, holdsLease: Boolean, command: BodyVelocityCommand): String? = when {
        !holdsLease -> "Virtual Stick command rejected: this port does not hold the lease"
        !state.enabled || state.owner != ControlOwner.APP -> "Virtual Stick authority unavailable: $state"
        !state.advancedMode -> "Virtual Stick advanced mode is not confirmed; command not submitted"
        !listOf(command.forwardMetersPerSecond, command.rightMetersPerSecond,
            command.upMetersPerSecond, command.yawRateDegreesPerSecond).all { it.isFinite() } ->
            "Virtual Stick command contains non-finite values"
        else -> null
    }

    fun acquireConfirmed(configured: Boolean, advancedObserved: Boolean,
                         state: FlightControlPortState, holdsLease: Boolean): Boolean =
        configured && advancedObserved && holdsLease && state.enabled && state.advancedMode && state.owner == ControlOwner.APP
}
