package edu.playground.djivln.domain.flight

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

class FlightControlSession(
    private val port: FlightControlPort,
    private val limiter: BodyVelocityCommandLimiter = BodyVelocityCommandLimiter(),
    private val watchdogMs: Long = 500L
) {
    enum class Phase {
        IDLE,
        ACQUIRING,
        ACTIVE,
        PAUSED_BY_OPERATOR,
        RELEASING,
        ERROR
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: UiText = UiText.resource(R.string.flight_control_disabled),
        val lastCommandAtMs: Long? = null,
        val zeroedByWatchdog: Boolean = false
    )

    private var current = State()
    private var listener: ((State) -> Unit)? = null

    fun start(listener: (State) -> Unit) {
        this.listener = listener
        port.start(::onPortState)
        publish(current)
    }

    fun acquire() {
        limiter.reset()
        publish(State(Phase.ACQUIRING, UiText.resource(R.string.flight_control_acquiring)))
        port.acquire { result ->
            result.onSuccess {
                if (port.state().owner == ControlOwner.APP) {
                    publish(current.copy(phase = Phase.ACTIVE, message = UiText.resource(R.string.flight_control_acquired)))
                } else {
                    publish(current.copy(message = UiText.resource(R.string.flight_control_waiting_authority)))
                }
            }.onFailure { error ->
                publish(State(Phase.ERROR, UiText.resource(R.string.flight_control_acquire_failed, error.message.orEmpty())))
            }
        }
    }

    fun submit(command: BodyVelocityCommand, timestampMs: Long): Result<Unit> {
        if (current.phase != Phase.ACTIVE) {
            return Result.failure(IllegalStateException("control session is ${current.phase}"))
        }
        val limited = limiter.limit(command, timestampMs)
        val result = port.send(limited)
        result.onSuccess {
            publish(current.copy(lastCommandAtMs = timestampMs, zeroedByWatchdog = false, message = UiText.resource(R.string.flight_control_command_sent)))
        }.onFailure { error ->
            publish(State(Phase.ERROR, UiText.resource(R.string.flight_control_command_failed, error.message.orEmpty()), current.lastCommandAtMs))
        }
        return result
    }

    fun tick(timestampMs: Long) {
        val lastCommandAtMs = current.lastCommandAtMs ?: return
        if (current.phase != Phase.ACTIVE || current.zeroedByWatchdog) return
        if (timestampMs - lastCommandAtMs <= watchdogMs) return
        limiter.reset(timestampMs)
        port.send(BodyVelocityCommand.ZERO).onSuccess {
            publish(current.copy(message = UiText.resource(R.string.flight_control_watchdog_hover), zeroedByWatchdog = true))
        }.onFailure { error ->
            publish(State(Phase.ERROR, UiText.resource(R.string.flight_control_watchdog_failed, error.message.orEmpty()), lastCommandAtMs))
        }
    }

    fun resumeAfterOperatorPause() {
        if (current.phase != Phase.PAUSED_BY_OPERATOR) return
        acquire()
    }

    fun release() {
        limiter.reset()
        publish(current.copy(phase = Phase.RELEASING, message = UiText.resource(R.string.flight_control_releasing)))
        port.release { result ->
            result.onSuccess { publish(State()) }
                .onFailure { error -> publish(State(Phase.ERROR, UiText.resource(R.string.flight_control_release_failed, error.message.orEmpty()))) }
        }
    }

    fun stop() {
        if (current.phase != Phase.IDLE) release()
        port.stop()
        listener = null
        current = State()
    }

    fun state(): State = current

    private fun onPortState(state: FlightControlPortState) {
        when {
            // RC is the expected owner while Virtual Stick is being acquired. Treat it as a
            // takeover only after this session has actually become active under APP authority.
            state.owner == ControlOwner.REMOTE_CONTROLLER && current.phase == Phase.ACTIVE -> {
                limiter.reset()
                publish(
                    current.copy(
                        phase = Phase.PAUSED_BY_OPERATOR,
                        message = UiText.resource(R.string.flight_control_operator_pause)
                    )
                )
            }
            state.enabled && state.owner == ControlOwner.APP && current.phase == Phase.ACQUIRING -> {
                publish(current.copy(phase = Phase.ACTIVE, message = UiText.resource(R.string.flight_control_acquired)))
            }
            state.lastError != null -> publish(State(Phase.ERROR, UiText.external(state.lastError)))
        }
    }

    private fun publish(state: State) {
        current = state
        listener?.invoke(state)
    }
}
