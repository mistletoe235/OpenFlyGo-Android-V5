package edu.playground.djivln.control

import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import edu.playground.djivln.domain.flight.FlightControlPortState
import org.junit.Assert.*
import org.junit.Test

class VirtualStickSendPolicyTest {
    private val ready = FlightControlPortState(enabled = true, advancedMode = true, owner = ControlOwner.APP)
    private val command = BodyVelocityCommand(forwardMetersPerSecond = 0.3)

    @Test fun enabledAndOwnedWithoutAdvancedModeCannotClaimSubmission() {
        assertNotNull(VirtualStickSendPolicy.issue(ready.copy(advancedMode = false), true, command))
        assertNotNull(VirtualStickSendPolicy.issue(ready.copy(advancedMode = false), true, BodyVelocityCommand.ZERO))
    }

    @Test fun configuredWithoutObservedAdvancedModeCannotCompleteAcquire() {
        assertFalse(VirtualStickSendPolicy.acquireConfirmed(true, false, ready, true))
        assertFalse(VirtualStickSendPolicy.acquireConfirmed(false, true, ready, true))
        assertTrue(VirtualStickSendPolicy.acquireConfirmed(true, true, ready, true))
    }

    @Test fun lossOfAdvancedModeAfterConfirmationInvalidatesReadiness() {
        assertFalse(VirtualStickSendPolicy.acquireConfirmed(true, true, ready.copy(advancedMode = false), true))
    }

    @Test fun leaseAuthorityAndEnabledStateRemainRequired() {
        for ((state, lease) in listOf(ready to false, ready.copy(enabled = false) to true,
            ready.copy(owner = ControlOwner.REMOTE_CONTROLLER) to true)) {
            assertNotNull(VirtualStickSendPolicy.issue(state, lease, command))
            assertFalse(VirtualStickSendPolicy.acquireConfirmed(true, true, state, lease))
        }
    }

    @Test fun validSmallVelocityAndZeroAreNotBlockedOrRaised() {
        assertNull(VirtualStickSendPolicy.issue(ready, true, BodyVelocityCommand.ZERO))
        assertNull(VirtualStickSendPolicy.issue(ready, true, command.copy(forwardMetersPerSecond = 0.01)))
        assertNull(VirtualStickSendPolicy.issue(ready, true, command.copy(forwardMetersPerSecond = -0.3)))
    }

    @Test fun nonfiniteAxesAreRejectedBeforeSdkInvocation() {
        for (invalid in listOf(command.copy(forwardMetersPerSecond = Double.NaN),
            command.copy(rightMetersPerSecond = Double.POSITIVE_INFINITY),
            command.copy(upMetersPerSecond = Double.NEGATIVE_INFINITY),
            command.copy(yawRateDegreesPerSecond = Double.NaN))) {
            assertNotNull(VirtualStickSendPolicy.issue(ready, true, invalid))
        }
    }
}
