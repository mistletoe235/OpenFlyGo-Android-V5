package edu.playground.djivln.adapter.dji

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthority
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import dji.v5.manager.interfaces.IVirtualStickManager
import edu.playground.djivln.control.BodyVelocityToDjiAxes
import edu.playground.djivln.control.ProcessVirtualStickPortLease
import edu.playground.djivln.control.VirtualStickLifecycleGuard
import edu.playground.djivln.control.VirtualStickPortLease
import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import edu.playground.djivln.domain.flight.FlightControlCompletion
import edu.playground.djivln.domain.flight.FlightControlPort
import edu.playground.djivln.domain.flight.FlightControlPortState
import edu.playground.djivln.domain.flight.FlightControlStateListener
import java.util.concurrent.TimeoutException

class DjiV5FlightControlPort(
    private val manager: IVirtualStickManager = VirtualStickManager.getInstance(),
    private val lease: VirtualStickPortLease = ProcessVirtualStickPortLease.instance,
) : FlightControlPort {
    private data class PendingAcquire(
        val token: VirtualStickLifecycleGuard.EnableToken,
        val completion: FlightControlCompletion,
        val timeout: Runnable,
        val advancedModeConfigured: Boolean = false,
    )

    private data class PendingRelease(
        val completion: FlightControlCompletion,
        val timeout: Runnable,
    )

    private val lifecycle = VirtualStickLifecycleGuard()
    private val leaseOwner = Any()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var listener: FlightControlStateListener? = null
    private var current = FlightControlPortState()
    private var started = false
    private var stopping = false
    private var actualEnabled = false
    private var actualOwner = ControlOwner.NONE
    private var pendingAcquire: PendingAcquire? = null
    private var pendingRelease: PendingRelease? = null
    private var pendingRollbackTimeout: Runnable? = null

    private val stateListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            val rawOwner = state.currentFlightControlAuthorityOwner.toOwner()
            lease.observeActual(
                enabled = state.isVirtualStickEnable,
                ownedByApp = rawOwner == ControlOwner.APP,
            )
            val action: VirtualStickLifecycleGuard.ObservationAction
            val next = synchronized(this@DjiV5FlightControlPort) {
                actualEnabled = state.isVirtualStickEnable
                actualOwner = rawOwner
                action = lifecycle.observeActual(state.isVirtualStickEnable)
                updateLocked {
                    it.copy(
                        enabled = state.isVirtualStickEnable,
                        advancedMode = state.isVirtualStickAdvancedModeEnabled,
                        owner = visibleOwner(rawOwner),
                        lastError = null,
                    )
                }
            }
            notifyState(next)

            if (action == VirtualStickLifecycleGuard.ObservationAction.FORCE_DISABLE &&
                lease.isHolder(leaseOwner)
            ) {
                requestDisableFromManager()
            }
            if (!state.isVirtualStickEnable) finishDisableFromObservedState()
            maybeCompleteAcquire()
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            val next = synchronized(this@DjiV5FlightControlPort) {
                val owner = if (reason.isRemoteTakeover()) {
                    ControlOwner.REMOTE_CONTROLLER
                } else {
                    visibleOwner(actualOwner)
                }
                updateLocked { it.copy(lastChangeReason = reason.name, owner = owner) }
            }
            notifyState(next)
        }
    }

    override fun start(listener: FlightControlStateListener) {
        val shouldRegister = synchronized(this) {
            this.listener = listener
            stopping = false
            if (started) {
                false
            } else {
                started = true
                lease.registerObserver()
                true
            }
        }
        if (shouldRegister) {
            runCatching { manager.setVirtualStickStateListener(stateListener) }
                .onFailure { error ->
                    synchronized(this) {
                        started = false
                        lease.unregisterObserver()
                        updateLocked { it.copy(lastError = error.message) }
                    }
                }
        }
        if (synchronized(this) { started }) seedActualStateFromKeyManager()
        listener.onStateChanged(state())
    }

    override fun stop() {
        val ownsLease: Boolean
        val releaseAlreadyPending: Boolean
        synchronized(this) {
            if (!started) return
            listener = null
            stopping = true
            ownsLease = lease.isHolder(leaseOwner)
            releaseAlreadyPending = pendingRelease != null
        }
        if (!ownsLease) {
            finalizeStopIfPossible()
        } else if (!releaseAlreadyPending) {
            release { finalizeStopIfPossible() }
        }
    }

    @Synchronized
    override fun state(): FlightControlPortState = current

    override fun acquire(completion: FlightControlCompletion) {
        var token: VirtualStickLifecycleGuard.EnableToken? = null
        var failure: Throwable? = null
        var next: FlightControlPortState? = null
        synchronized(this) {
            failure = when {
                !started -> IllegalStateException("Virtual Stick port is not started")
                stopping -> IllegalStateException("Virtual Stick port is stopping")
                pendingAcquire != null -> IllegalStateException("Virtual Stick acquire is already pending")
                pendingRelease != null -> IllegalStateException("Virtual Stick release is pending")
                else -> lease.tryAcquire(leaseOwner)?.asException()
            }
            if (failure == null) {
                token = lifecycle.requestEnable()
                val currentToken = token!!
                val timeout = Runnable { onAcquireTimeout(currentToken) }
                pendingAcquire = PendingAcquire(currentToken, completion, timeout)
                next = updateLocked { it.copy(requested = true, lastError = null) }
                timeoutHandler.postDelayed(timeout, ACQUIRE_TIMEOUT_MILLIS)
            }
        }
        next?.let(::notifyState)
        failure?.let {
            completion.complete(Result.failure(it))
            return
        }

        val currentToken = token ?: return
        runCatching {
            manager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = onEnableAccepted(currentToken)

                override fun onFailure(error: IDJIError) {
                    rollbackAcquire(currentToken, DjiOperationException(error))
                }
            })
        }.onFailure { rollbackAcquire(currentToken, it) }
    }

    override fun release(completion: FlightControlCompletion) {
        var failure: Throwable? = null
        var cancelledAcquire: FlightControlCompletion? = null
        var next: FlightControlPortState? = null
        synchronized(this) {
            failure = when {
                !lease.isHolder(leaseOwner) ->
                    IllegalStateException("Virtual Stick release rejected: this port does not hold the lease")
                pendingRelease != null -> IllegalStateException("Virtual Stick release is already pending")
                else -> null
            }
            if (failure == null) {
                pendingAcquire?.let {
                    timeoutHandler.removeCallbacks(it.timeout)
                    cancelledAcquire = it.completion
                }
                pendingAcquire = null
                lifecycle.requestDisable()
                val timeout = Runnable { onReleaseTimeout() }
                pendingRelease = PendingRelease(completion, timeout)
                next = updateLocked { it.copy(requested = false, lastError = null) }
                timeoutHandler.postDelayed(timeout, RELEASE_TIMEOUT_MILLIS)
            }
        }
        failure?.let {
            completion.complete(Result.failure(it))
            return
        }
        next?.let(::notifyState)
        cancelledAcquire?.complete(
            Result.failure(IllegalStateException("Virtual Stick acquire cancelled by release")),
        )

        if (!lease.isHolder(leaseOwner)) {
            finishDisableSuccess()
            return
        }
        runCatching {
            if (actualEnabled && actualOwner == ControlOwner.APP) {
                manager.sendVirtualStickAdvancedParam(buildParam(BodyVelocityCommand.ZERO))
            }
        }
        requestDisableFromManager()
    }

    override fun send(command: BodyVelocityCommand): Result<Unit> {
        if (!lease.isHolder(leaseOwner)) {
            return Result.failure(
                IllegalStateException("Virtual Stick command rejected: this port does not hold the lease"),
            )
        }
        val snapshot = state()
        if (!snapshot.enabled || snapshot.owner != ControlOwner.APP) {
            return Result.failure(IllegalStateException("Virtual Stick authority unavailable: $snapshot"))
        }
        return runCatching { manager.sendVirtualStickAdvancedParam(buildParam(command)) }
    }

    override fun takeoff(completion: FlightControlCompletion) =
        performAction(FlightControllerKey.KeyStartTakeoff, completion)

    override fun land(completion: FlightControlCompletion) =
        performAction(FlightControllerKey.KeyStartAutoLanding, completion)

    override fun cancelLanding(completion: FlightControlCompletion) =
        performAction(FlightControllerKey.KeyStopAutoLanding, completion)

    override fun confirmLanding(completion: FlightControlCompletion) =
        performAction(FlightControllerKey.KeyConfirmLanding, completion)

    override fun returnHome(completion: FlightControlCompletion) =
        performAction(FlightControllerKey.KeyStartGoHome, completion)

    override fun cancelReturnHome(completion: FlightControlCompletion) =
        performAction(FlightControllerKey.KeyStopGoHome, completion)

    private fun onEnableAccepted(token: VirtualStickLifecycleGuard.EnableToken) {
        val accepted = synchronized(this) {
            val pending = pendingAcquire
            pending?.token === token && lifecycle.accepts(token) && lease.isHolder(leaseOwner)
        }
        if (!accepted) {
            if (lease.isHolder(leaseOwner) && !lifecycle.wantsEnabled()) requestDisableFromManager()
            return
        }

        runCatching { manager.setVirtualStickAdvancedModeEnabled(true) }
            .onFailure {
                rollbackAcquire(token, it)
                return
            }

        synchronized(this) {
            val pending = pendingAcquire
            if (pending?.token === token && lifecycle.accepts(token) && lease.isHolder(leaseOwner)) {
                pendingAcquire = pending.copy(advancedModeConfigured = true)
            }
        }
        maybeCompleteAcquire()
    }

    private fun maybeCompleteAcquire() {
        var completion: FlightControlCompletion? = null
        synchronized(this) {
            val pending = pendingAcquire ?: return
            if (!pending.advancedModeConfigured ||
                !actualEnabled ||
                actualOwner != ControlOwner.APP ||
                !lifecycle.accepts(pending.token) ||
                !lease.isHolder(leaseOwner)
            ) return
            timeoutHandler.removeCallbacks(pending.timeout)
            pendingAcquire = null
            completion = pending.completion
        }
        completion?.complete(Result.success(Unit))
    }

    private fun onAcquireTimeout(token: VirtualStickLifecycleGuard.EnableToken) {
        rollbackAcquire(
            token,
            TimeoutException("Virtual Stick enable state was not confirmed within ${ACQUIRE_TIMEOUT_MILLIS}ms"),
        )
    }

    private fun rollbackAcquire(token: VirtualStickLifecycleGuard.EnableToken, error: Throwable) {
        var completion: FlightControlCompletion? = null
        var next: FlightControlPortState? = null
        synchronized(this) {
            val pending = pendingAcquire ?: return
            if (pending.token !== token) return
            timeoutHandler.removeCallbacks(pending.timeout)
            pendingAcquire = null
            lifecycle.requestDisable()
            completion = pending.completion
            next = updateLocked { it.copy(requested = false, lastError = error.message) }
            pendingRollbackTimeout?.let(timeoutHandler::removeCallbacks)
            pendingRollbackTimeout = Runnable { finishRollbackTimeout() }.also {
                timeoutHandler.postDelayed(it, RELEASE_TIMEOUT_MILLIS)
            }
        }
        next?.let(::notifyState)
        completion?.complete(Result.failure(error))
        requestDisableFromManager()
    }

    private fun requestDisableFromManager() {
        val shouldRequest = synchronized(this) {
            lease.isHolder(leaseOwner) && lifecycle.claimDisableCommand()
        }
        if (!shouldRequest) return
        runCatching { manager.setVirtualStickAdvancedModeEnabled(false) }
        runCatching {
            manager.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = finishDisableSuccess()

                override fun onFailure(error: IDJIError) =
                    finishDisableFailure(DjiOperationException(error))
            })
        }.onFailure(::finishDisableFailure)
    }

    private fun finishDisableFromObservedState() {
        val shouldFinish = synchronized(this) {
            lease.isHolder(leaseOwner) && !lifecycle.wantsEnabled()
        }
        if (shouldFinish) finishDisableSuccess()
    }

    private fun finishDisableSuccess() {
        var completion: FlightControlCompletion? = null
        var next: FlightControlPortState? = null
        synchronized(this) {
            if (!lease.isHolder(leaseOwner)) return
            lease.release(leaseOwner)
            pendingRollbackTimeout?.let(timeoutHandler::removeCallbacks)
            pendingRollbackTimeout = null
            pendingRelease?.let {
                timeoutHandler.removeCallbacks(it.timeout)
                completion = it.completion
            }
            pendingRelease = null
            next = updateLocked {
                it.copy(
                    requested = false,
                    owner = visibleOwner(actualOwner),
                    lastError = null,
                )
            }
        }
        next?.let(::notifyState)
        completion?.complete(Result.success(Unit))
        finalizeStopIfPossible()
    }

    private fun finishDisableFailure(error: Throwable) {
        var completion: FlightControlCompletion? = null
        var completedDespiteError = false
        var next: FlightControlPortState? = null
        synchronized(this) {
            lifecycle.releaseDisableCommandClaim()
            if (!lease.isHolder(leaseOwner)) return
            val disabledWasObserved = !lease.snapshot().actualEnabled
            if (disabledWasObserved) {
                lease.release(leaseOwner)
                pendingRollbackTimeout?.let(timeoutHandler::removeCallbacks)
                pendingRollbackTimeout = null
            }
            pendingRelease?.let {
                timeoutHandler.removeCallbacks(it.timeout)
                completion = it.completion
            }
            pendingRelease = null
            completedDespiteError = disabledWasObserved
            next = updateLocked {
                it.copy(
                    requested = false,
                    owner = visibleOwner(actualOwner),
                    lastError = if (disabledWasObserved) null else error.message,
                )
            }
        }
        next?.let(::notifyState)
        completion?.complete(
            if (completedDespiteError) Result.success(Unit) else Result.failure(error),
        )
        finalizeStopIfPossible()
    }

    private fun finishRollbackTimeout() {
        var next: FlightControlPortState? = null
        synchronized(this) {
            pendingRollbackTimeout = null
            if (!lease.isHolder(leaseOwner) || lifecycle.wantsEnabled()) return
            if (!lease.snapshot().actualEnabled) {
                lease.release(leaseOwner)
                next = updateLocked {
                    it.copy(requested = false, owner = visibleOwner(actualOwner))
                }
            }
        }
        next?.let(::notifyState)
        finalizeStopIfPossible()
    }

    private fun onReleaseTimeout() {
        var completion: FlightControlCompletion? = null
        val error = TimeoutException(
            "Virtual Stick disable was not confirmed within ${RELEASE_TIMEOUT_MILLIS}ms",
        )
        val next = synchronized(this) {
            val pending = pendingRelease ?: return
            pendingRelease = null
            completion = pending.completion
            updateLocked { it.copy(requested = false, lastError = error.message) }
        }
        notifyState(next)
        completion?.complete(Result.failure(error))
        // Retain the lease while DJI still reports enabled. A later observed disabled state will
        // release it; this prevents a second port from entering an uncertain control session.
    }

    private fun finalizeStopIfPossible() {
        val shouldUnregister = synchronized(this) {
            if (!stopping || !started || lease.isHolder(leaseOwner)) {
                false
            } else {
                pendingAcquire?.let { timeoutHandler.removeCallbacks(it.timeout) }
                pendingRelease?.let { timeoutHandler.removeCallbacks(it.timeout) }
                pendingRollbackTimeout?.let(timeoutHandler::removeCallbacks)
                pendingAcquire = null
                pendingRelease = null
                pendingRollbackTimeout = null
                started = false
                stopping = false
                current = FlightControlPortState()
                true
            }
        }
        if (shouldUnregister) {
            manager.removeVirtualStickStateListener(stateListener)
            lease.unregisterObserver()
        }
    }

    private fun seedActualStateFromKeyManager() {
        val keyManager = KeyManager.getInstance()
        val enabled = runCatching {
            keyManager.getValue(KeyTools.createKey(FlightControllerKey.KeyVirtualStickEnabled))
        }.getOrNull() ?: return
        val owner = runCatching {
            keyManager.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightControlCurrentAuthority))
        }.getOrNull().toOwner()
        val advancedMode = runCatching {
            keyManager.getValue(KeyTools.createKey(FlightControllerKey.KeyVirtualStickControlModeEnabled))
        }.getOrNull() ?: false

        lease.observeActual(enabled = enabled, ownedByApp = owner == ControlOwner.APP)
        val next = synchronized(this) {
            actualEnabled = enabled
            actualOwner = owner
            lifecycle.observeActual(enabled)
            updateLocked {
                it.copy(
                    enabled = enabled,
                    advancedMode = advancedMode,
                    owner = visibleOwner(owner),
                )
            }
        }
        notifyState(next)
    }

    private fun buildParam(command: BodyVelocityCommand): VirtualStickFlightControlParam {
        val axes = BodyVelocityToDjiAxes.map(
            forward = command.forwardMetersPerSecond,
            right = command.rightMetersPerSecond,
            up = command.upMetersPerSecond,
            yawRateDegreesPerSecond = command.yawRateDegreesPerSecond,
        )
        return VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            pitch = axes.pitch
            roll = axes.roll
            verticalThrottle = axes.verticalThrottle
            yaw = axes.yaw
        }
    }

    private fun performAction(
        key: DJIActionKeyInfo<EmptyMsg, EmptyMsg>,
        completion: FlightControlCompletion,
    ) {
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(key),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(value: EmptyMsg) = completion.complete(Result.success(Unit))
                    override fun onFailure(error: IDJIError) =
                        completion.complete(Result.failure(DjiOperationException(error)))
                },
            )
        }.onFailure { completion.complete(Result.failure(it)) }
    }

    private fun notifyState(state: FlightControlPortState) {
        val currentListener = synchronized(this) { listener }
        currentListener?.onStateChanged(state)
    }

    private fun updateLocked(
        reducer: (FlightControlPortState) -> FlightControlPortState,
    ): FlightControlPortState {
        current = reducer(current)
        return current
    }

    private fun visibleOwner(rawOwner: ControlOwner): ControlOwner =
        if (rawOwner == ControlOwner.APP && !lease.isHolder(leaseOwner)) {
            ControlOwner.UNKNOWN
        } else {
            rawOwner
        }

    private fun VirtualStickPortLease.AcquireBlock.asException(): IllegalStateException =
        IllegalStateException(
            when (this) {
                VirtualStickPortLease.AcquireBlock.STATE_NOT_OBSERVED ->
                    "Virtual Stick actual state has not been observed yet; retry after telemetry updates"
                VirtualStickPortLease.AcquireBlock.EXTERNAL_SESSION_ACTIVE ->
                    "Virtual Stick is already enabled without a local port lease"
                VirtualStickPortLease.AcquireBlock.ANOTHER_PORT_HOLDS_LEASE ->
                    "Virtual Stick is controlled by another local feature"
                VirtualStickPortLease.AcquireBlock.PORT_ALREADY_HOLDS_LEASE ->
                    "This port already holds the Virtual Stick lease"
            },
        )

    private fun FlightControlAuthority?.toOwner(): ControlOwner = when (this) {
        FlightControlAuthority.MSDK -> ControlOwner.APP
        FlightControlAuthority.RC -> ControlOwner.REMOTE_CONTROLLER
        FlightControlAuthority.AUTO_TEST,
        FlightControlAuthority.OSDK,
        FlightControlAuthority.AIRPORT -> ControlOwner.AUTOPILOT
        FlightControlAuthority.UNKNOWN,
        null -> ControlOwner.UNKNOWN
    }

    private fun FlightControlAuthorityChangeReason.isRemoteTakeover(): Boolean = when (this) {
        FlightControlAuthorityChangeReason.RC_SWITCH,
        FlightControlAuthorityChangeReason.RC_PAUSE_STOP,
        FlightControlAuthorityChangeReason.RC_ONE_KEY_GO_HOME,
        FlightControlAuthorityChangeReason.RC_NOT_P_MODE -> true
        else -> false
    }

    private companion object {
        const val ACQUIRE_TIMEOUT_MILLIS = 8_000L
        const val RELEASE_TIMEOUT_MILLIS = 8_000L
    }
}

class DjiOperationException(error: IDJIError) : IllegalStateException(error.toString())
