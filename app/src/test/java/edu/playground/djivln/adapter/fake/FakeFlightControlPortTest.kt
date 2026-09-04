package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeFlightControlPortTest {
    @Test
    fun rejectsCommandsUntilAuthorityIsAcquired() {
        val port = FakeFlightControlPort()

        assertTrue(port.send(BodyVelocityCommand(forwardMetersPerSecond = 1.0)).isFailure)
        port.acquire { assertTrue(it.isSuccess) }
        assertTrue(port.send(BodyVelocityCommand(forwardMetersPerSecond = 1.0)).isSuccess)
        assertEquals(1.0, port.commands.single().forwardMetersPerSecond, 0.0)
    }

    @Test
    fun manualTakeoverRevokesAuthorityWithoutAbortingPort() {
        val port = FakeFlightControlPort()
        port.acquire { }

        port.simulateManualTakeover()

        assertFalse(port.state().enabled)
        assertEquals(ControlOwner.REMOTE_CONTROLLER, port.state().owner)
        assertTrue(port.send(BodyVelocityCommand(rightMetersPerSecond = 1.0)).isFailure)
    }

    @Test
    fun releaseSendsZeroBeforeClearingState() {
        val port = FakeFlightControlPort()
        port.acquire { }
        port.send(BodyVelocityCommand(forwardMetersPerSecond = 2.0))

        port.release { assertTrue(it.isSuccess) }

        assertEquals(BodyVelocityCommand.ZERO, port.commands.last())
        assertFalse(port.state().requested)
    }
}
