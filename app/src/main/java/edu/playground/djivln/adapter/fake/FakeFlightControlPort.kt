package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import edu.playground.djivln.domain.flight.FlightControlCompletion
import edu.playground.djivln.domain.flight.FlightControlPort
import edu.playground.djivln.domain.flight.FlightControlPortState
import edu.playground.djivln.domain.flight.FlightControlStateListener

class FakeFlightControlPort : FlightControlPort {
    private var listener: FlightControlStateListener? = null
    private var current = FlightControlPortState()
    private val mutableCommands = mutableListOf<BodyVelocityCommand>()

    val commands: List<BodyVelocityCommand> get() = mutableCommands.toList()
    var lastAction: String? = null
        private set

    override fun start(listener: FlightControlStateListener) {
        this.listener = listener
        listener.onStateChanged(current)
    }

    override fun stop() {
        listener = null
    }

    override fun state(): FlightControlPortState = current

    override fun acquire(completion: FlightControlCompletion) {
        current = current.copy(requested = true, enabled = true, advancedMode = true, owner = ControlOwner.APP)
        publish()
        completion.complete(Result.success(Unit))
    }

    override fun release(completion: FlightControlCompletion) {
        if (current.enabled) mutableCommands += BodyVelocityCommand.ZERO
        current = FlightControlPortState()
        publish()
        completion.complete(Result.success(Unit))
    }

    override fun send(command: BodyVelocityCommand): Result<Unit> {
        if (!current.enabled || current.owner != ControlOwner.APP) {
            return Result.failure(IllegalStateException("flight control authority unavailable"))
        }
        mutableCommands += command
        return Result.success(Unit)
    }

    override fun takeoff(completion: FlightControlCompletion) = action("takeoff", completion)

    override fun land(completion: FlightControlCompletion) = action("land", completion)

    override fun cancelLanding(completion: FlightControlCompletion) = action("cancelLanding", completion)

    override fun returnHome(completion: FlightControlCompletion) = action("returnHome", completion)

    override fun cancelReturnHome(completion: FlightControlCompletion) = action("cancelReturnHome", completion)

    fun simulateManualTakeover(reason: String = "remote controller stick moved") {
        current = current.copy(enabled = false, owner = ControlOwner.REMOTE_CONTROLLER, lastChangeReason = reason)
        publish()
    }

    private fun action(name: String, completion: FlightControlCompletion) {
        lastAction = name
        completion.complete(Result.success(Unit))
    }

    private fun publish() {
        listener?.onStateChanged(current)
    }
}
