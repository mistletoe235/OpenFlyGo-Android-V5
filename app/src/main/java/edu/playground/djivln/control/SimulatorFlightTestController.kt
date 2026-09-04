package edu.playground.djivln.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import edu.playground.djivln.R
import edu.playground.djivln.logging.AppDiagnosticLogger
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.SimulatorState
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.domain.flight.VirtualStickCadence
import edu.playground.djivln.control.ControlVelocityCommand

/**
 * Flight-test harness that is intentionally impossible to arm unless DJI's on-aircraft simulator
 * reports itself enabled. It is kept separate from the VLN dry-run controller so simulator tests
 * cannot accidentally arm the model-control path.
 */
class SimulatorFlightTestController(
    context: Context,
    private val onSnapshot: (Snapshot) -> Unit,
    private val onPositionActionTerminal: (PositionActionTerminal) -> Unit = {},
    private val sendFrequencyHz: Int = DEFAULT_SEND_FREQUENCY_HZ,
) {
    data class PositionActionTerminal(
        val successful: Boolean,
        val reason: String,
        val horizontalErrorMeters: Double,
        val verticalErrorMeters: Double,
    )
    enum class Phase {
        OFF,
        STARTING_SIMULATOR,
        SIMULATOR_READY,
        TAKING_OFF,
        FLYING_MANUAL,
        ENABLING_VIRTUAL_STICK,
        VIRTUAL_STICK_READY,
        EXECUTING,
        LANDING,
        ERROR
    }

    data class Snapshot(
        val phase: Phase = Phase.OFF,
        val simulatorEnabled: Boolean = false,
        val motorsOn: Boolean = false,
        val flying: Boolean = false,
        val virtualStickEnabled: Boolean = false,
        val advancedModeEnabled: Boolean = false,
        val positionX: Float? = null,
        val positionY: Float? = null,
        val positionZ: Float? = null,
        val message: String = ""
    )

    private val appContext = context.applicationContext

    private val handler = Handler(Looper.getMainLooper())
    private val simulatorManager = SimulatorManager.getInstance()
    private val virtualStickManager = VirtualStickManager.getInstance()
    private var snapshot = Snapshot(message = text(R.string.simulator_not_started))
    private var simulatorState: SimulatorState? = null
    private var activeParam: VirtualStickFlightControlParam? = null
    private var stopCommandAtMs: Long? = null
    private var externalSimulationArmed = false
    private var externalSimulationAirborne = false
    private val externalSimulationInterlock = ExternalSimulationInterlock()
    private val virtualStickLifecycle = VirtualStickLifecycleGuard()
    private var enableAttempt = 0
    private var lastStateLogSignature: String? = null
    private var destroyed = false
    private val gpsPositionController = GpsPositionClosedLoop(context = appContext)
    private val positionCommandLimiter = VelocityCommandSlewLimiter()
    private var latestControlPose: PositionControlPose? = null
    private var timedActionStartedNanos: Long? = null
    private var timedActionFirstSendNanos: Long? = null
    private var timedActionLastSendNanos: Long? = null
    private var timedActionFirstMovementNanos: Long? = null
    private var timedActionSendCount = 0
    private var timedActionStartPosition: Triple<Float, Float, Float>? = null

    init {
        require(sendFrequencyHz in MIN_SEND_FREQUENCY_HZ..MAX_SEND_FREQUENCY_HZ) {
            "Virtual Stick send frequency must be in $MIN_SEND_FREQUENCY_HZ..$MAX_SEND_FREQUENCY_HZ Hz"
        }
    }

    private val sendRunnable = object : Runnable {
        override fun run() {
            if ((!simulatorManager.isSimulatorEnabled() && !externalSimulationArmed) || !snapshot.virtualStickEnabled) {
                stopCommandStream(text(R.string.simulator_safety_lock_triggered))
                return
            }
            if (gpsPositionController.isActive()) {
                val step = gpsPositionController.step(effectiveControlPose(), android.os.SystemClock.elapsedRealtime())
                if (step.terminal) {
                    Log.i(TAG, "position loop terminal success=${step.successful} reason=${step.reason} error=${step.horizontalErrorMeters}")
                    virtualStickManager.sendVirtualStickAdvancedParam(velocityParam())
                    hold(if (step.successful) text(R.string.simulator_position_reached) else text(R.string.simulator_position_aborted, step.reason))
                    onPositionActionTerminal(
                        PositionActionTerminal(
                            successful = step.successful,
                            reason = step.reason,
                            horizontalErrorMeters = step.horizontalErrorMeters,
                            verticalErrorMeters = step.verticalErrorMeters,
                        ),
                    )
                    return
                }
                val limited = positionCommandLimiter.limit(step.command, android.os.SystemClock.elapsedRealtime())
                gpsPositionController.recordAppliedCommand(limited)
                activeParam = velocityParam(
                    forward = limited.vxMetersPerSecond,
                    right = limited.vyMetersPerSecond,
                    up = limited.vzMetersPerSecond,
                    yawRate = limited.yawRateDegreesPerSecond,
                )
            }
            val param = activeParam ?: return
            recordTimedActionSend()
            virtualStickManager.sendVirtualStickAdvancedParam(param)
            val stopAt = stopCommandAtMs
            if (stopAt != null && android.os.SystemClock.elapsedRealtime() >= stopAt) {
                finishTimedActionMetrics()
                hold(text(R.string.simulator_timed_action_complete))
                return
            }
            handler.postDelayed(this, sendIntervalMillis())
        }
    }

    private val simulatorListener = SimulatorStatusListener { state ->
        handler.post {
            simulatorState = state
            recordTimedActionMovement(state)
            val enabled = simulatorManager.isSimulatorEnabled()
            val phase = when {
                !enabled -> Phase.OFF
                snapshot.phase == Phase.TAKING_OFF && state.isFlying() -> Phase.FLYING_MANUAL
                snapshot.phase == Phase.LANDING && !state.areMotorsOn() -> Phase.SIMULATOR_READY
                snapshot.virtualStickEnabled -> snapshot.phase
                state.isFlying() -> Phase.FLYING_MANUAL
                else -> Phase.SIMULATOR_READY
            }
            update(
                phase = phase,
                simulatorEnabled = enabled,
                motorsOn = state.areMotorsOn(),
                flying = state.isFlying(),
                positionX = state.positionX,
                positionY = state.positionY,
                positionZ = state.positionZ,
                message = when {
                    !enabled -> text(R.string.simulator_not_started)
                    state.isFlying() -> snapshot.message
                    else -> text(R.string.simulator_waiting_takeoff)
                }
            )
        }
    }

    private val virtualStickListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            handler.post {
                if (destroyed) return@post
                val lifecycleAction = virtualStickLifecycle.observeActual(state.isVirtualStickEnable)
                if (lifecycleAction == VirtualStickLifecycleGuard.ObservationAction.FORCE_DISABLE) {
                    forceDisableVirtualStick(text(R.string.simulator_late_authority_release))
                    return@post
                }
                update(
                    phase = when {
                        state.isVirtualStickEnable && simulatorState?.isFlying() == true -> Phase.VIRTUAL_STICK_READY
                        simulatorState?.isFlying() == true -> Phase.FLYING_MANUAL
                        simulatorManager.isSimulatorEnabled() -> Phase.SIMULATOR_READY
                        else -> Phase.OFF
                    },
                    virtualStickEnabled = state.isVirtualStickEnable,
                    advancedModeEnabled = state.isVirtualStickAdvancedModeEnabled,
                    message = when (lifecycleAction) {
                        VirtualStickLifecycleGuard.ObservationAction.ACCEPT_ENABLED -> text(R.string.simulator_vs_confirmed)
                        VirtualStickLifecycleGuard.ObservationAction.CONFIRMED_DISABLED -> text(R.string.simulator_vs_released)
                        else -> if (state.isVirtualStickEnable && !snapshot.virtualStickEnabled) {
                            text(R.string.simulator_vs_acquired)
                        } else {
                            snapshot.message
                        }
                    }
                )
                if (lifecycleAction == VirtualStickLifecycleGuard.ObservationAction.ACCEPT_ENABLED &&
                    state.isVirtualStickAdvancedModeEnabled) {
                    hold(text(R.string.simulator_vs_zero_sent))
                }
            }
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            handler.post {
                if (snapshot.virtualStickEnabled) {
                    update(message = text(R.string.simulator_authority_changed, reason.name))
                }
            }
        }
    }

    init {
        simulatorManager.addSimulatorStateListener(simulatorListener)
        virtualStickManager.setVirtualStickStateListener(virtualStickListener)
        refresh()
    }

    fun currentSnapshot(): Snapshot = snapshot

    fun refresh() {
        val enabled = simulatorManager.isSimulatorEnabled()
        update(
            phase = if (enabled) Phase.SIMULATOR_READY else Phase.OFF,
            simulatorEnabled = enabled,
            message = if (enabled) text(R.string.simulator_waiting_state) else text(R.string.simulator_real_control_locked)
        )
    }

    fun takeOff() {
        if (!requireSimulator(text(R.string.action_takeoff))) return
        if (simulatorState?.isFlying() == true) {
            fail(text(R.string.simulator_already_airborne))
            return
        }
        update(phase = Phase.TAKING_OFF, message = text(R.string.simulator_takeoff_sent))
        performFlightAction(
            key = FlightControllerKey.KeyStartTakeoff,
            success = { update(message = text(R.string.simulator_takeoff_accepted)) },
            failurePrefix = text(R.string.simulator_takeoff_failed)
        )
    }

    fun enableVirtualStick() {
        if (!requireSimulator(text(R.string.action_enable_virtual_stick))) return
        beginVirtualStickEnable(text(R.string.simulator_requesting_authority))
    }

    /** Explicit opt-in for an aircraft-side/hardware simulator that MSDK SimulatorManager cannot see. */
    fun enableExternalSimulationVirtualStick(airborne: Boolean) {
        if (!externalSimulationInterlock.isConfirmed()) {
            fail(text(R.string.external_simulation_confirmation_required))
            return
        }
        if (!DjiSdkBootstrap.snapshot().connected || !airborne) {
            fail(text(R.string.external_simulation_aircraft_unavailable))
            return
        }
        externalSimulationArmed = true
        externalSimulationAirborne = true
        beginVirtualStickEnable(text(R.string.external_simulation_requesting_authority))
    }

    fun confirmExternalSimulationForCurrentSession() {
        externalSimulationInterlock.confirmForCurrentSession()
    }

    fun moveForwardTwoSeconds() {
        executeTimed(text(R.string.simulator_action_forward), velocityParam(forward = 0.5), ACTION_DURATION_MS)
    }

    fun moveLeftTwoSeconds() {
        executeTimed(text(R.string.simulator_action_left), velocityParam(right = -0.5), ACTION_DURATION_MS)
    }

    fun rotateClockwiseTwoSeconds() {
        executeTimed(text(R.string.simulator_action_clockwise), velocityParam(yawRate = 15.0), ACTION_DURATION_MS)
    }

    fun hold(message: String? = null) {
        if (!requireVirtualStick(text(R.string.action_hover))) return
        gpsPositionController.cancel()
        positionCommandLimiter.reset(android.os.SystemClock.elapsedRealtime())
        activeParam = velocityParam()
        stopCommandAtMs = null
        handler.removeCallbacks(sendRunnable)
        update(phase = Phase.VIRTUAL_STICK_READY, message = message ?: text(R.string.simulator_hover_zero))
        handler.post(sendRunnable)
    }

    /** Applies a safety-gated VLN velocity command only while DJI simulator control is armed. */
    fun applyVlnCommand(command: ControlVelocityCommand, message: String? = null): Boolean {
        if (!requireVirtualStick(text(R.string.action_vln_control))) return false
        val pose = effectiveControlPose()
        if (pose == null) {
            fail(text(R.string.simulator_vln_pose_missing))
            hold(text(R.string.simulator_position_blocked_pose_missing))
            return false
        }
        val issue = gpsPositionController.start(command, pose, android.os.SystemClock.elapsedRealtime())
        if (issue != null) {
            fail(text(R.string.simulator_vln_position_blocked, issue))
            hold(text(R.string.simulator_position_blocked_zero, issue))
            return false
        }
        activeParam = velocityParam()
        positionCommandLimiter.reset(android.os.SystemClock.elapsedRealtime())
        Log.i(
            TAG,
            "position loop start source=${pose.source} heading=${pose.headingDegrees} " +
                "forward=${command.targetForwardMeters} right=${command.targetRightMeters} up=${command.targetUpMeters}",
        )
        stopCommandAtMs = null
        handler.removeCallbacks(sendRunnable)
        update(
            phase = Phase.EXECUTING,
            message = text(
                R.string.simulator_position_target,
                message ?: text(R.string.simulator_vln_control),
                command.targetForwardMeters,
                command.targetRightMeters,
                command.targetUpMeters,
                pose.source,
            ),
        )
        handler.post(sendRunnable)
        return true
    }

    fun updateControlPose(pose: PositionControlPose?) {
        latestControlPose = pose
    }

    fun setMaximumHorizontalSpeed(metersPerSecond: Double) {
        gpsPositionController.setMaximumHorizontalSpeed(metersPerSecond)
    }

    fun setPositionClosureMode(mode: PositionClosureMode) {
        gpsPositionController.setMode(mode)
    }

    fun positionClosureMode(): PositionClosureMode = gpsPositionController.currentMode()

    fun isPositionActionActive(): Boolean = gpsPositionController.isActive()

    private fun effectiveControlPose(): PositionControlPose? {
        val pose = latestControlPose ?: return null
        return if (externalSimulationArmed && !pose.rtkFixed) {
            pose.copy(source = "EXTERNAL_SIMULATOR_GPS", simulated = true)
        } else {
            pose
        }
    }

    fun land() {
        if (!requireSimulator(text(R.string.action_land))) return
        if (simulatorState?.isFlying() != true) {
            fail(text(R.string.simulator_not_airborne))
            return
        }
        stopCommandStream(text(R.string.simulator_prepare_landing))
        releaseVirtualStick(text(R.string.simulator_release_before_landing)) {
            update(phase = Phase.LANDING, message = text(R.string.simulator_landing_sent))
            performFlightAction(
                key = FlightControllerKey.KeyStartAutoLanding,
                success = { update(message = text(R.string.simulator_landing_accepted)) },
                failurePrefix = text(R.string.simulator_landing_failed)
            )
        }
    }

    /** Zero command + give control back to the RC. It deliberately does not stop motors. */
    fun abort() {
        stopCommandStream(text(R.string.simulator_test_abort_zeroed))
        releaseVirtualStick(text(R.string.simulator_test_abort_releasing)) {
            update(
                phase = if (simulatorState?.isFlying() == true) Phase.FLYING_MANUAL else if (simulatorManager.isSimulatorEnabled()) Phase.SIMULATOR_READY else Phase.OFF,
                message = text(R.string.simulator_test_aborted)
            )
        }
    }

    fun onAircraftDisconnected() {
        stopCommandStream(text(R.string.simulator_disconnect_zeroed))
        releaseVirtualStick(text(R.string.simulator_disconnect_releasing)) {
            externalSimulationArmed = false
            externalSimulationAirborne = false
            externalSimulationInterlock.revoke()
            update(
                phase = Phase.OFF,
                simulatorEnabled = false,
                flying = false,
                message = text(R.string.simulator_disconnect_released),
            )
        }
    }

    fun destroy() {
        destroyed = true
        stopCommandStream(text(R.string.simulator_page_destroyed))
        virtualStickLifecycle.requestDisable()
        if (virtualStickLifecycle.managesSession() || snapshot.virtualStickEnabled) forceDisableVirtualStick(text(R.string.simulator_page_destroyed))
        handler.removeCallbacksAndMessages(null)
        simulatorManager.removeSimulatorStateListener(simulatorListener)
        virtualStickManager.removeVirtualStickStateListener(virtualStickListener)
    }

    private fun executeTimed(label: String, param: VirtualStickFlightControlParam, durationMs: Long) {
        if (!requireVirtualStick(label)) return
        activeParam = param
        stopCommandAtMs = android.os.SystemClock.elapsedRealtime() + durationMs
        startTimedActionMetrics()
        handler.removeCallbacks(sendRunnable)
        update(phase = Phase.EXECUTING, message = text(R.string.simulator_executing, label, sendFrequencyHz))
        handler.post(sendRunnable)
    }

    private fun startTimedActionMetrics() {
        timedActionStartedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        timedActionFirstSendNanos = null
        timedActionLastSendNanos = null
        timedActionFirstMovementNanos = null
        timedActionSendCount = 0
        timedActionStartPosition = simulatorState?.let { Triple(it.positionX, it.positionY, it.positionZ) }
        AppDiagnosticLogger.info(
            TAG,
            "VS_RATE_TEST phase=start requested=${sendFrequencyHz}Hz interval=${sendIntervalMillis()}ms",
        )
    }

    private fun recordTimedActionSend() {
        if (timedActionStartedNanos == null) return
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (timedActionFirstSendNanos == null) timedActionFirstSendNanos = now
        timedActionLastSendNanos = now
        timedActionSendCount += 1
    }

    private fun recordTimedActionMovement(state: SimulatorState) {
        if (timedActionStartedNanos == null || timedActionFirstMovementNanos != null) return
        val start = timedActionStartPosition ?: return
        val dx = state.positionX - start.first
        val dy = state.positionY - start.second
        val dz = state.positionZ - start.third
        if (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) >= MOVEMENT_DETECTION_METERS) {
            timedActionFirstMovementNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun finishTimedActionMetrics() {
        val started = timedActionStartedNanos ?: return
        val firstSend = timedActionFirstSendNanos
        val lastSend = timedActionLastSendNanos
        val measuredHz = if (firstSend != null && lastSend != null && lastSend > firstSend && timedActionSendCount > 1) {
            (timedActionSendCount - 1) * 1_000_000_000.0 / (lastSend - firstSend)
        } else {
            0.0
        }
        val movementLatencyMs = timedActionFirstMovementNanos?.let { (it - started) / 1_000_000.0 }
        val displacement = timedActionStartPosition?.let { start ->
            simulatorState?.let { state ->
                val dx = state.positionX - start.first
                val dy = state.positionY - start.second
                val dz = state.positionZ - start.third
                kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toDouble()
            }
        }
        val message = "VS_RATE_TEST RESULT=PASS requested=${sendFrequencyHz}Hz " +
            "sent=$timedActionSendCount measured=${"%.2f".format(java.util.Locale.US, measuredHz)}Hz " +
            "firstMovementMs=${movementLatencyMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "--"} " +
            "displacementM=${displacement?.let { "%.3f".format(java.util.Locale.US, it) } ?: "--"}"
        AppDiagnosticLogger.info(TAG, message)
        timedActionStartedNanos = null
        timedActionFirstSendNanos = null
        timedActionLastSendNanos = null
        timedActionFirstMovementNanos = null
        timedActionSendCount = 0
        timedActionStartPosition = null
    }

    private fun sendIntervalMillis(): Long = (1_000L / sendFrequencyHz).coerceAtLeast(1L)

    private fun stopCommandStream(message: String) {
        handler.removeCallbacks(sendRunnable)
        gpsPositionController.cancel()
        positionCommandLimiter.reset(android.os.SystemClock.elapsedRealtime())
        if (snapshot.virtualStickEnabled && (simulatorManager.isSimulatorEnabled() || externalSimulationArmed)) {
            virtualStickManager.sendVirtualStickAdvancedParam(velocityParam())
        }
        activeParam = null
        stopCommandAtMs = null
        update(message = message)
    }

    private fun releaseVirtualStick(message: String, then: () -> Unit) {
        handler.removeCallbacks(sendRunnable)
        gpsPositionController.cancel()
        positionCommandLimiter.reset(android.os.SystemClock.elapsedRealtime())
        activeParam = null
        stopCommandAtMs = null
        val hadManagedSession = virtualStickLifecycle.managesSession()
        val disableToken = virtualStickLifecycle.requestDisable()
        externalSimulationInterlock.revoke()
        if (!snapshot.virtualStickEnabled && !hadManagedSession) {
            then()
            return
        }
        runCatching { virtualStickManager.sendVirtualStickAdvancedParam(velocityParam()) }
        virtualStickManager.setVirtualStickAdvancedModeEnabled(false)
        virtualStickManager.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                handler.post {
                    if (!virtualStickLifecycle.accepts(disableToken) || destroyed) return@post
                    update(virtualStickEnabled = false, advancedModeEnabled = false, message = message)
                    scheduleReleaseVerification(message, disableToken)
                    then()
                }
            }

            override fun onFailure(error: IDJIError) {
                handler.post {
                    if (!virtualStickLifecycle.accepts(disableToken) || destroyed) return@post
                    fail(text(R.string.simulator_vs_release_failed, error))
                }
            }
        })
    }

    private fun beginVirtualStickEnable(message: String) {
        val token = virtualStickLifecycle.requestEnable()
        enableAttempt = 0
        update(
            phase = Phase.ENABLING_VIRTUAL_STICK,
            virtualStickEnabled = false,
            advancedModeEnabled = false,
            message = message,
        )
        attemptVirtualStickEnable(token)
    }

    private fun attemptVirtualStickEnable(token: VirtualStickLifecycleGuard.EnableToken) {
        if (!virtualStickLifecycle.accepts(token) || destroyed) return
        if (snapshot.virtualStickEnabled && snapshot.advancedModeEnabled) return
        enableAttempt += 1
        val attempt = enableAttempt
        update(message = text(R.string.simulator_vs_request_attempt, attempt, MAX_ENABLE_ATTEMPTS))
        virtualStickManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                handler.post {
                    if (!virtualStickLifecycle.accepts(token) || destroyed) {
                        forceDisableVirtualStick(text(R.string.simulator_enable_callback_expired))
                        return@post
                    }
                    if (attempt != enableAttempt) return@post
                    virtualStickManager.setVirtualStickAdvancedModeEnabled(true)
                    update(message = text(R.string.simulator_vs_request_accepted))
                    scheduleEnableVerification(token, attempt)
                }
            }

            override fun onFailure(error: IDJIError) {
                handler.post {
                    if (!virtualStickLifecycle.accepts(token) || destroyed) return@post
                    if (attempt != enableAttempt) return@post
                    if (attempt < MAX_ENABLE_ATTEMPTS && canRetryVirtualStickEnable()) {
                        update(message = text(R.string.simulator_vs_request_retry, attempt, error))
                        handler.postDelayed({ attemptVirtualStickEnable(token) }, ENABLE_RETRY_DELAY_MS)
                    } else {
                        virtualStickLifecycle.requestDisable()
                        fail(text(R.string.simulator_vs_enable_failed, error))
                    }
                }
            }
        })
    }

    private fun scheduleEnableVerification(
        token: VirtualStickLifecycleGuard.EnableToken,
        attempt: Int,
    ) {
        handler.postDelayed({
            if (!virtualStickLifecycle.accepts(token) || destroyed) return@postDelayed
            if (attempt != enableAttempt) return@postDelayed
            if (snapshot.virtualStickEnabled && snapshot.advancedModeEnabled) return@postDelayed
            if (attempt < MAX_ENABLE_ATTEMPTS && canRetryVirtualStickEnable()) {
                update(message = text(R.string.simulator_vs_state_retry))
                attemptVirtualStickEnable(token)
            } else {
                virtualStickLifecycle.requestDisable()
                forceDisableVirtualStick(text(R.string.simulator_vs_state_timeout_released))
                fail(text(R.string.simulator_vs_state_timeout))
            }
        }, ENABLE_VERIFY_DELAY_MS)
    }

    private fun canRetryVirtualStickEnable(): Boolean =
        DjiSdkBootstrap.snapshot().connected &&
            (simulatorState?.isFlying() == true || externalSimulationAirborne)

    private fun scheduleReleaseVerification(
        message: String,
        token: VirtualStickLifecycleGuard.DisableToken,
    ) {
        handler.postDelayed({
            if (destroyed || !virtualStickLifecycle.accepts(token) || !virtualStickLifecycle.managesSession()) {
                return@postDelayed
            }
            if (snapshot.virtualStickEnabled) {
                forceDisableVirtualStick(text(R.string.simulator_still_enabled_released, message))
            }
        }, RELEASE_VERIFY_DELAY_MS)
    }

    private fun forceDisableVirtualStick(message: String) {
        handler.removeCallbacks(sendRunnable)
        gpsPositionController.cancel()
        positionCommandLimiter.reset(android.os.SystemClock.elapsedRealtime())
        activeParam = null
        stopCommandAtMs = null
        runCatching { virtualStickManager.sendVirtualStickAdvancedParam(velocityParam()) }
        runCatching { virtualStickManager.setVirtualStickAdvancedModeEnabled(false) }
        runCatching { virtualStickManager.disableVirtualStick(null) }
        update(virtualStickEnabled = false, advancedModeEnabled = false, message = message)
    }

    private fun requireSimulator(action: String): Boolean {
        if (!DjiSdkBootstrap.snapshot().connected) {
            fail(text(R.string.simulator_action_blocked_disconnected, action))
            return false
        }
        if (!simulatorManager.isSimulatorEnabled() && !externalSimulationArmed) {
            fail(text(R.string.simulator_action_blocked_not_started, action))
            return false
        }
        return true
    }

    private fun requireVirtualStick(action: String): Boolean {
        if (!requireSimulator(action)) return false
        if (simulatorState?.isFlying() != true && !externalSimulationAirborne) {
            fail(text(R.string.simulator_action_blocked_not_airborne, action))
            return false
        }
        if (!snapshot.virtualStickEnabled || !snapshot.advancedModeEnabled) {
            fail(text(R.string.simulator_action_blocked_vs_unavailable, action))
            return false
        }
        return true
    }

    private fun performFlightAction(
        key: dji.sdk.keyvalue.key.DJIActionKeyInfo<dji.sdk.keyvalue.value.common.EmptyMsg, EmptyMsg>,
        success: () -> Unit,
        failurePrefix: String
    ) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(key),
            actionCompletion(success, failurePrefix)
        )
    }

    private fun actionCompletion(
        success: () -> Unit,
        failurePrefix: String
    ): CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
        return object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(value: EmptyMsg) {
                handler.post(success)
            }

            override fun onFailure(error: IDJIError) {
                handler.post { fail("$failurePrefix：$error") }
            }
        }
    }

    private fun completion(
        success: () -> Unit,
        failurePrefix: String
    ): CommonCallbacks.CompletionCallback {
        return object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                handler.post(success)
            }

            override fun onFailure(error: IDJIError) {
                handler.post { fail("$failurePrefix：$error") }
            }
        }
    }

    private fun velocityParam(
        forward: Double = 0.0,
        right: Double = 0.0,
        up: Double = 0.0,
        yawRate: Double = 0.0
    ): VirtualStickFlightControlParam {
        val axes = BodyVelocityToDjiAxes.map(forward, right, up, yawRate)
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

    private fun fail(message: String) {
        AppDiagnosticLogger.error(TAG, message)
        update(phase = Phase.ERROR, message = message)
    }

    private fun text(resourceId: Int, vararg arguments: Any?): String =
        appContext.getString(resourceId, *arguments)

    private fun update(
        phase: Phase = snapshot.phase,
        simulatorEnabled: Boolean = snapshot.simulatorEnabled,
        motorsOn: Boolean = snapshot.motorsOn,
        flying: Boolean = snapshot.flying,
        virtualStickEnabled: Boolean = snapshot.virtualStickEnabled,
        advancedModeEnabled: Boolean = snapshot.advancedModeEnabled,
        positionX: Float? = snapshot.positionX,
        positionY: Float? = snapshot.positionY,
        positionZ: Float? = snapshot.positionZ,
        message: String = snapshot.message
    ) {
        snapshot = Snapshot(
            phase,
            simulatorEnabled,
            motorsOn,
            flying,
            virtualStickEnabled,
            advancedModeEnabled,
            positionX,
            positionY,
            positionZ,
            message
        )
        val logSignature = "$phase|$virtualStickEnabled|$advancedModeEnabled|$message"
        if (logSignature != lastStateLogSignature &&
            (virtualStickEnabled || phase == Phase.ERROR || phase == Phase.ENABLING_VIRTUAL_STICK)) {
            lastStateLogSignature = logSignature
            Log.i(TAG, "phase=$phase vs=$virtualStickEnabled advanced=$advancedModeEnabled message=$message")
        }
        onSnapshot(snapshot)
    }

    private companion object {
        const val TAG = "VlnSimControl"
        const val DEFAULT_SEND_FREQUENCY_HZ = VirtualStickCadence.FREQUENCY_HZ
        const val MIN_SEND_FREQUENCY_HZ = 5
        const val MAX_SEND_FREQUENCY_HZ = 100
        const val MOVEMENT_DETECTION_METERS = 0.02f
        const val ACTION_DURATION_MS = 2_000L
        const val MAX_ENABLE_ATTEMPTS = 3
        const val ENABLE_RETRY_DELAY_MS = 300L
        const val ENABLE_VERIFY_DELAY_MS = 600L
        const val RELEASE_VERIFY_DELAY_MS = 500L
    }
}
