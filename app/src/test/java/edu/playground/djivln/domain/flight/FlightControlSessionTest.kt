package edu.playground.djivln.domain.flight

import edu.playground.djivln.adapter.fake.FakeFlightControlPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightControlSessionTest {
    @Test
    fun staleCommandIsZeroedExactlyOnce() {
        val port = FakeFlightControlPort()
        val session = FlightControlSession(port, watchdogMs = 500L)
        session.start { }
        session.acquire()
        session.submit(BodyVelocityCommand(forwardMetersPerSecond = 2.0), 1_000L)

        session.tick(1_501L)
        session.tick(2_000L)

        assertEquals(2, port.commands.size)
        assertEquals(BodyVelocityCommand.ZERO, port.commands.last())
        assertTrue(session.state().zeroedByWatchdog)
    }

    @Test
    fun remoteControllerTakeoverPausesAndCanReacquire() {
        val port = FakeFlightControlPort()
        val session = FlightControlSession(port)
        session.start { }
        session.acquire()

        port.simulateManualTakeover()

        assertEquals(FlightControlSession.Phase.PAUSED_BY_OPERATOR, session.state().phase)
        session.resumeAfterOperatorPause()
        assertEquals(FlightControlSession.Phase.ACTIVE, session.state().phase)
    }

    @Test
    fun remoteControllerOwnershipWhileAcquiringDoesNotPauseSession() {
        val port = DelayedAcquirePort()
        val session = FlightControlSession(port)
        session.start { }

        session.acquire()

        assertEquals(FlightControlSession.Phase.ACQUIRING, session.state().phase)
        port.completeAcquire()
        assertEquals(FlightControlSession.Phase.ACTIVE, session.state().phase)
    }

    @Test
    fun horizontalLimitUsesVectorMagnitude() {
        val limiter = BodyVelocityCommandLimiter(
            BodyVelocityLimits(
                maxHorizontalMetersPerSecond = 1.0,
                maxHorizontalAcceleration = 100.0
            )
        )

        val result = limiter.limit(BodyVelocityCommand(2.0, 2.0), 1_000L)

        assertEquals(1.0, kotlin.math.hypot(result.forwardMetersPerSecond, result.rightMetersPerSecond), 0.0001)
    }

    private class DelayedAcquirePort : FlightControlPort {
        private var listener: FlightControlStateListener? = null
        private var current = FlightControlPortState(owner = ControlOwner.REMOTE_CONTROLLER)
        private var completion: FlightControlCompletion? = null

        override fun start(listener: FlightControlStateListener) {
            this.listener = listener
            listener.onStateChanged(current)
        }

        override fun stop() = Unit
        override fun state(): FlightControlPortState = current

        override fun acquire(completion: FlightControlCompletion) {
            this.completion = completion
            current = current.copy(requested = true)
            listener?.onStateChanged(current)
        }

        fun completeAcquire() {
            current = current.copy(enabled = true, advancedMode = true, owner = ControlOwner.APP)
            listener?.onStateChanged(current)
            completion?.complete(Result.success(Unit))
        }

        override fun release(completion: FlightControlCompletion) = completion.complete(Result.success(Unit))
        override fun send(command: BodyVelocityCommand): Result<Unit> = Result.success(Unit)
        override fun takeoff(completion: FlightControlCompletion) = completion.complete(Result.success(Unit))
        override fun land(completion: FlightControlCompletion) = completion.complete(Result.success(Unit))
        override fun cancelLanding(completion: FlightControlCompletion) = completion.complete(Result.success(Unit))
        override fun returnHome(completion: FlightControlCompletion) = completion.complete(Result.success(Unit))
        override fun cancelReturnHome(completion: FlightControlCompletion) = completion.complete(Result.success(Unit))
    }
}
