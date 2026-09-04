package edu.playground.djivln.domain.flight

data class BodyVelocityCommand(
    val forwardMetersPerSecond: Double = 0.0,
    val rightMetersPerSecond: Double = 0.0,
    val upMetersPerSecond: Double = 0.0,
    val yawRateDegreesPerSecond: Double = 0.0
) {
    companion object {
        val ZERO = BodyVelocityCommand()
    }
}

enum class ControlOwner {
    NONE,
    APP,
    REMOTE_CONTROLLER,
    AUTOPILOT,
    UNKNOWN
}

data class FlightControlPortState(
    val requested: Boolean = false,
    val enabled: Boolean = false,
    val advancedMode: Boolean = false,
    val owner: ControlOwner = ControlOwner.NONE,
    val lastChangeReason: String? = null,
    val lastError: String? = null
)

fun interface FlightControlStateListener {
    fun onStateChanged(state: FlightControlPortState)
}

fun interface FlightControlCompletion {
    fun complete(result: Result<Unit>)
}

interface FlightControlPort {
    fun start(listener: FlightControlStateListener)
    fun stop()
    fun state(): FlightControlPortState
    fun acquire(completion: FlightControlCompletion)
    fun release(completion: FlightControlCompletion)
    fun send(command: BodyVelocityCommand): Result<Unit>
    fun takeoff(completion: FlightControlCompletion)
    fun land(completion: FlightControlCompletion)
    fun cancelLanding(completion: FlightControlCompletion)
    fun confirmLanding(completion: FlightControlCompletion) {
        completion.complete(Result.failure(UnsupportedOperationException("landing confirmation unavailable")))
    }
    fun returnHome(completion: FlightControlCompletion)
    fun cancelReturnHome(completion: FlightControlCompletion)
}
