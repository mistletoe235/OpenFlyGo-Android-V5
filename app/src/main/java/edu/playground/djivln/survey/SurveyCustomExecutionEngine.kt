package edu.playground.djivln.survey

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import dji.sdk.keyvalue.value.common.ComponentIndexType
import edu.playground.djivln.camera.CameraCaptureController
import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import edu.playground.djivln.domain.flight.FlightControlPort
import edu.playground.djivln.domain.flight.FlightControlPortState
import edu.playground.djivln.domain.flight.VirtualStickCadence
import edu.playground.djivln.domain.gimbal.GimbalPort
import edu.playground.djivln.domain.gimbal.GimbalState
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.hil.HilFrameProtocol
import edu.playground.djivln.R
import kotlin.math.abs
import kotlin.math.hypot

enum class SurveyExecutionBackend(@StringRes val labelRes: Int) {
    DJI_KMZ(R.string.survey_backend_dji_kmz),
    CUSTOM_VIRTUAL_STICK(R.string.survey_backend_custom_virtual_stick),
    UE_HIL(R.string.survey_backend_ue_hil),
}

data class SurveyCustomExecutionSnapshot(
    val status: SurveyExecutionStatus = SurveyExecutionStatus(SurveyExecutionState.IDLE, 0, null),
    val phase: SurveyExecutionPhase? = null,
    val executionLegIndex: Int = 0,
    val executionLegCount: Int = 0,
    val currentSectionSeconds: Double = 0.0,
    val totalRemainingSeconds: Double = 0.0,
    val currentTarget: GeoPoint? = null,
    val recoveryPoint: GeoPoint? = null,
    val message: String = "",
    val controlAcquirePending: Boolean = false,
)

/** Generation guard for an asynchronous Virtual Stick acquire. */
internal class SurveyAcquireRequestGuard {
    enum class Mode { START, RESUME }

    class Token internal constructor(
        internal val generation: Long,
        val mode: Mode,
    )

    private var generation = 0L
    private var active: Token? = null

    fun begin(mode: Mode): Token = Token(++generation, mode).also { active = it }

    fun isCurrent(token: Token): Boolean = active == token

    fun consume(token: Token): Boolean {
        if (!isCurrent(token)) return false
        active = null
        return true
    }

    fun cancel(): Boolean {
        if (active == null) return false
        generation += 1
        active = null
        return true
    }

    val pending: Boolean get() = active != null
    val resuming: Boolean get() = active?.mode == Mode.RESUME
}

class SurveyCustomExecutionEngine(
    private val context: Context,
    private val flightPort: FlightControlPort,
    private val gimbalPortFactory: (ComponentIndexType) -> GimbalPort,
    private val snapshot: () -> AircraftSnapshot,
    private val cameraIndex: () -> ComponentIndexType = { ComponentIndexType.LEFT_OR_MAIN },
    private val cameraGeometryMatches: (SurveyMission) -> Boolean = { false },
    private val latestHilFrame: (maxAgeMillis: Long) -> HilFrameProtocol.Frame? = { null },
    private val saveHilCapture: (
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        callback: (Result<String>) -> Unit,
    ) -> Unit = { _, _, _, callback ->
        callback(Result.failure(IllegalStateException(context.getString(R.string.hil_image_saver_not_configured))))
    },
    private val onPhotoTriggered: (SurveyPhotoTrigger) -> Unit = {},
    private val onCommand: (BodyVelocityCommand) -> Unit = {},
    private val onEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    private val hilPeerHost: () -> String? = { null },
    private val onSnapshot: (SurveyCustomExecutionSnapshot) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val captureController = SurveyDistanceCaptureController()
    private val cameraController = CameraCaptureController(context) { }
    private val ueBridge = SurveyUeBridgeClient(context)
    private var machine: SurveySimulatorExecutionStateMachine? = null
    private var mission: SurveyMission? = null
    private var backend = SurveyExecutionBackend.CUSTOM_VIRTUAL_STICK
    private var ueEndpoint = ""
    private var flightState = FlightControlPortState()
    private var latest = SurveyCustomExecutionSnapshot(message = context.getString(R.string.not_started))
    private var lastTargetLeg = -1
    private var captureSequence = 0L
    private var closed = false
    private var activeGimbalPort: GimbalPort? = null
    private var gimbalState = GimbalState()
    private var gimbalCommandedPitch = Double.NaN
    private var gimbalSettlingStartedElapsedMillis = 0L
    private var gimbalLastCommandElapsedMillis = 0L
    private var gimbalCommandAttempts = 0
    private var gimbalCommandGeneration = 0L
    private var gimbalCommandAcceptedElapsedMillis = 0L
    private var gimbalLimitActive = false
    private var pendingCaptureStart: SurveyWaypoint? = null
    private var pendingPointCaptureLeg: Int? = null
    private var completedPointCaptureLeg: Int? = null
    private var waypointDeadlineElapsedMillis = 0L
    private var legStartedElapsedMillis = 0L
    private var photoInFlight = false
    private var captureRequestGeneration = 0L
    private var captureTimeoutRunnable: Runnable? = null
    private val acquireGuard = SurveyAcquireRequestGuard()
    private var restoredCheckpoint: SurveyExecutionCheckpoint? = null
    private var autoTakeoffPending = false
    private var autoTakeoffDeadlineElapsedMillis = 0L
    private var autoTakeoffFlyingSinceElapsedMillis = 0L
    private var returnHomeHandoffGeneration = 0L
    private var returnHomeHandoffPending = false
    private val autoTakeoffTick = object : Runnable {
        override fun run() {
            val currentMission = mission
            if (!autoTakeoffPending || currentMission == null) return
            val aircraft = snapshot()
            val now = SystemClock.elapsedRealtime()
            if (!aircraft.connected || !aircraft.simulatorActive || aircraft.sticksActive) {
                cancelAutoTakeoff(context.getString(R.string.auto_takeoff_safety_lost))
                return
            }
            if (now >= autoTakeoffDeadlineElapsedMillis) {
                cancelAutoTakeoff(context.getString(R.string.auto_takeoff_confirmation_timeout))
                return
            }
            val stableHover = aircraft.isFlying &&
                (aircraft.relativeAltitudeMeters ?: 0.0) >= 0.8 &&
                kotlin.math.abs(aircraft.velocity?.up ?: Double.POSITIVE_INFINITY) <= 0.5
            if (stableHover) {
                if (autoTakeoffFlyingSinceElapsedMillis == 0L) {
                    autoTakeoffFlyingSinceElapsedMillis = now
                }
                if (now - autoTakeoffFlyingSinceElapsedMillis >= AUTO_TAKEOFF_STABLE_MILLIS) {
                    autoTakeoffPending = false
                    handler.removeCallbacks(this)
                    armAndAcquire(currentMission)
                    return
                }
            } else {
                autoTakeoffFlyingSinceElapsedMillis = 0L
            }
            handler.postDelayed(this, AUTO_TAKEOFF_POLL_MILLIS)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val currentMachine = machine ?: return
            val currentMission = mission ?: return
            if (currentMachine.status.state != SurveyExecutionState.RUNNING) return
            val aircraft = snapshot()
            val pose = aircraft.toFollowerPose()
            if (pose == null) {
                pauseInternal(context.getString(R.string.execution_pose_unavailable))
                return
            }
            if (shouldHandoffToDjiReturnHome(currentMission, currentMachine)) {
                handoffToDjiReturnHome(currentMachine, pose)
                return
            }
            val now = SystemClock.elapsedRealtime()
            val runtimeGate = gate(currentMission, requireVirtualStick = true, checkPreflight = false)
            val failsafe = SurveyExecutionWatchdog.inspect(
                state = currentMachine.status.state,
                gate = runtimeGate,
                trustedDjiTelemetry = true,
                nowElapsedMillis = now,
                waypointDeadlineElapsedMillis = waypointDeadlineElapsedMillis,
            )
            when (failsafe.action) {
                SurveyFailsafeAction.CONTINUE -> Unit
                SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE -> {
                    pauseInternal(failsafe.reason ?: context.getString(R.string.execution_safety_temporarily_lost))
                    return
                }
                SurveyFailsafeAction.ABORT_ZERO_AND_RELEASE -> {
                    abortInternal(failsafe.reason ?: context.getString(R.string.execution_safety_gate_failed))
                    return
                }
            }
            val target = currentMachine.currentTarget
            if (lastTargetLeg != currentMachine.executionLegIndex) {
                lastTargetLeg = currentMachine.executionLegIndex
                beginTarget(target, pose, currentMission, currentMachine)
                postTarget(currentMission, currentMachine)
            }
            val actualGimbalPitch = gimbalState.pitchDegrees ?: aircraft.gimbalPitchDegrees ?: Double.NaN
            val gimbalSettled = actualGimbalPitch.isFinite() &&
                SurveyGimbalSettlePolicy.isVerifiedForCapture(
                    targetPitchDegrees = target.gimbalPitchDegrees,
                    actualPitchDegrees = actualGimbalPitch,
                    commandAcceptedElapsedMillis = gimbalCommandAcceptedElapsedMillis,
                    nowElapsedMillis = now,
                )
            val nadirPitchLimited = SurveyNadirGimbalPolicy.isMechanicalLimit(
                targetPitchDegrees = target.gimbalPitchDegrees,
                settled = gimbalSettled,
                pitchAtStop = gimbalState.pitchLimited,
            )
            if (nadirPitchLimited != gimbalLimitActive) {
                gimbalLimitActive = nadirPitchLimited
                publish(context.getString(
                    R.string.gimbal_lower_limit_status,
                    context.getString(if (nadirPitchLimited) R.string.status_active else R.string.status_cleared),
                    "%.0f".format(target.gimbalPitchDegrees),
                    "%.1f".format(actualGimbalPitch),
                ))
            }
            gimbalSettlingStartedElapsedMillis = SurveyGimbalSettlePolicy.updateUnsettledSince(
                nowElapsedMillis = now,
                unsettledSinceElapsedMillis = gimbalSettlingStartedElapsedMillis,
                settled = gimbalSettled || nadirPitchLimited,
            )
            if (!gimbalSettled && !nadirPitchLimited && SurveyGimbalSettlePolicy.hasTimedOut(
                    now,
                    gimbalSettlingStartedElapsedMillis,
                )
            ) {
                pauseInternal(
                    "gimbal pitch timeout target=${"%.0f".format(target.gimbalPitchDegrees)}° " +
                        "actual=${"%.1f".format(actualGimbalPitch)}° attempts=$gimbalCommandAttempts",
                )
                return
            }
            if (!gimbalSettled && !nadirPitchLimited && SurveyGimbalSettlePolicy.shouldRetry(
                    now,
                    gimbalLastCommandElapsedMillis,
                )
            ) commandGimbal(target.gimbalPitchDegrees, now)

            val maximumVerticalSpeed = verticalSpeedLimit(
                currentMission,
                pose.altitudeMeters,
                target.point.altitudeMeters,
            )
            val command = SurveyWaypointFollower.command(
                pose = pose,
                target = target,
                maximumHorizontalSpeedMetersPerSecond = currentMission.constraints
                    .speedForCaptureView(target.captureView),
                maximumVerticalSpeedMetersPerSecond = maximumVerticalSpeed,
                alignHeadingBeforeHorizontalMotion = currentMachine.requiresHeadingAlignmentBeforeTranslation,
            )
            val position = GeoPoint(pose.latitude, pose.longitude, pose.altitudeMeters)
            val captureReady = captureReady()
            pendingCaptureStart?.takeIf { gimbalSettled && captureReady }?.let { pending ->
                pendingCaptureStart = null
                if (captureController.onWaypointReached(pending, position, now, true)) {
                    requestCapture(position, currentMachine, "DELAYED_START_DISTANCE_INTERVAL")
                }
            }
            if (!command.reached &&
                currentMachine.currentPhase == SurveyExecutionPhase.SURVEY &&
                SurveyNadirGimbalPolicy.canCapture(gimbalSettled) &&
                captureController.onPosition(
                    position,
                    now,
                    captureReady,
                    hypot(
                        aircraft.velocity?.north ?: 0.0,
                        aircraft.velocity?.east ?: 0.0,
                    ),
                )
            ) requestCapture(position, currentMachine, "interval")
            if (command.reached) {
                val stopsCapture = target.captureAction == CaptureAction.STOP_DISTANCE_INTERVAL
                val capturesOnReach = target.captureAction == CaptureAction.CAPTURE_ON_REACH
                val deferNadirCaptureStart = SurveyNadirGimbalPolicy.shouldDeferCaptureStart(
                    target.captureAction,
                    nadirPitchLimited,
                )
                if (!deferNadirCaptureStart) send(BodyVelocityCommand.ZERO)
                if (!gimbalSettled && !deferNadirCaptureStart && !stopsCapture) {
                    postTelemetry(currentMachine, aircraft)
                    scheduleTick()
                    return
                }
                if (stopsCapture && pendingCaptureStart != null) {
                    pendingCaptureStart = null
                    captureController.reset()
                    pauseInternal(context.getString(R.string.nadir_capture_blocked_by_gimbal_limit))
                    return
                }
                if (capturesOnReach && completedPointCaptureLeg != currentMachine.executionLegIndex) {
                    send(BodyVelocityCommand.ZERO)
                    if (!gimbalSettled || photoInFlight || !captureReady) {
                        postTelemetry(currentMachine, aircraft)
                        scheduleTick()
                        return
                    }
                    pendingPointCaptureLeg = currentMachine.executionLegIndex
                    if (captureController.onWaypointReached(target, position, now, true)) {
                        requestCapture(position, currentMachine, target.captureAction.name)
                    } else {
                        pendingPointCaptureLeg = null
                    }
                    postTelemetry(currentMachine, aircraft)
                    scheduleTick()
                    return
                }
                when {
                    deferNadirCaptureStart -> pendingCaptureStart = target
                    SurveyNadirGimbalPolicy.shouldSkipEndFrame(target.captureAction, gimbalSettled) -> {
                        captureController.reset()
                    }
                    captureController.onWaypointReached(target, position, now, captureReady) -> {
                        requestCapture(position, currentMachine, target.captureAction.name)
                    }
                }
                if (stopsCapture && (captureController.active || photoInFlight)) {
                    postTelemetry(currentMachine, aircraft)
                    scheduleTick()
                    return
                }
                val next = currentMachine.reachWaypoint()
                lastTargetLeg = -1
                if (next.state == SurveyExecutionState.COMPLETED) {
                    finish(context.getString(R.string.route_execution_completed))
                    return
                }
                if (shouldHandoffToDjiReturnHome(currentMission, currentMachine)) {
                    handoffToDjiReturnHome(currentMachine, pose)
                    return
                }
                setWaypointDeadline(currentMachine, snapshot().toFollowerPose(), currentMission)
            } else {
                send(BodyVelocityCommand(
                    forwardMetersPerSecond = command.forwardMetersPerSecond,
                    rightMetersPerSecond = command.rightMetersPerSecond,
                    upMetersPerSecond = command.upMetersPerSecond,
                    yawRateDegreesPerSecond = command.yawRateDegreesPerSecond,
                ))
            }
            postTelemetry(currentMachine, aircraft)
            publish(context.getString(R.string.executing_horizontal_error, "%.1f".format(command.horizontalErrorMeters)))
            scheduleTick()
        }
    }

    init {
        flightPort.start { state ->
            flightState = state
            if (machine?.status?.state == SurveyExecutionState.RUNNING &&
                !returnHomeHandoffPending &&
                state.owner == ControlOwner.REMOTE_CONTROLLER
            ) pauseInternal(context.getString(R.string.remote_controller_takeover))
        }
    }

    fun start(mission: SurveyMission, backend: SurveyExecutionBackend, ueEndpoint: String) {
        if (backend == SurveyExecutionBackend.DJI_KMZ) return
        stop(context.getString(R.string.switching_execution_mission))
        restoredCheckpoint = null
        this.mission = mission
        this.backend = backend
        this.ueEndpoint = ueEndpoint.trim()
        if (backend == SurveyExecutionBackend.UE_HIL) runCatching { activeUeEndpoint() }
            .onFailure { publish(context.getString(R.string.ue_hil_address_invalid, it.message ?: it.javaClass.simpleName)); return }
        val currentCameraIndex = cameraIndex()
        if (backend != SurveyExecutionBackend.UE_HIL && currentCameraIndex == ComponentIndexType.UNKNOWN) {
            publish(context.getString(R.string.start_blocked_no_camera))
            return
        }
        if (backend != SurveyExecutionBackend.UE_HIL) {
            cameraController.bind(currentCameraIndex, force = true)
            cameraController.selectPhotoMode()
        }
        bindGimbal(
            currentCameraIndex.takeUnless { it == ComponentIndexType.UNKNOWN }
                ?: ComponentIndexType.LEFT_OR_MAIN,
        )
        resetRuntimeTracking()
        val aircraft = snapshot()
        val autoTakeoff = mission.constraints.takeoffMode == SurveyTakeoffMode.AUTO_SIMULATOR_ONLY
        if (autoTakeoff && !aircraft.simulatorActive) {
            publish(context.getString(R.string.start_blocked_auto_takeoff_simulator_only))
            return
        }
        if (autoTakeoff && !aircraft.isFlying) {
            startAutoTakeoff(mission)
            return
        }
        armAndAcquire(mission)
    }

    private fun armAndAcquire(mission: SurveyMission) {
        val current = snapshot().toSurveyPoint()
        val created = SurveySimulatorExecutionStateMachine(
            mission = mission,
            currentPoint = current,
            returnPoint = currentReturnPoint() ?: current,
        )
        machine = created
        captureController.configure(
            mission.constraints.captureTriggerMode,
            mission.constraints.timedCaptureIntervalSeconds,
        )
        val preflight = gate(mission, requireVirtualStick = false, checkPreflight = true)
        val armed = created.requestArm(preflight)
        if (armed.state != SurveyExecutionState.ARMING) {
            publish(context.getString(R.string.start_blocked_detail, preflight.blocks.joinToString()))
            return
        }
        val acquireToken = acquireGuard.begin(SurveyAcquireRequestGuard.Mode.START)
        publish(context.getString(R.string.requesting_virtual_stick))
        if (backend == SurveyExecutionBackend.UE_HIL) {
            val endpoint = activeUeEndpointOrPause() ?: run {
                acquireGuard.cancel()
                created.abort(context.getString(R.string.ue_hil_peer_unavailable))
                publish(created.status.reason ?: context.getString(R.string.ue_hil_peer_unavailable))
                return
            }
            ueBridge.postMission(endpoint, mission) { ok, message ->
                handler.post { if (!ok) publish(context.getString(R.string.ue_mission_sync_failed, message)) }
            }
        }
        flightPort.acquire { result ->
            handler.post {
                if (!acquireGuard.isCurrent(acquireToken) || machine !== created || this.mission !== mission) {
                    return@post
                }
                acquireGuard.consume(acquireToken)
                result.onFailure {
                    if (created.status.state == SurveyExecutionState.ARMING) {
                        created.abort(context.getString(R.string.virtual_stick_acquire_failed_detail, it.message ?: it.javaClass.simpleName))
                    }
                    publish(created.status.reason ?: context.getString(R.string.virtual_stick_acquire_failed))
                }.onSuccess {
                    val running = created.onVirtualStickReady(
                        gate(mission, requireVirtualStick = true, checkPreflight = false),
                    )
                    if (running.state == SurveyExecutionState.RUNNING) {
                        beginRunning(currentMission = mission, message = context.getString(R.string.custom_route_started))
                    } else {
                        zeroAndRelease(running.reason ?: context.getString(R.string.execution_gate_not_passed))
                    }
                }
            }
        }
    }

    private fun startAutoTakeoff(mission: SurveyMission) {
        if (autoTakeoffPending) {
            publish(context.getString(R.string.auto_takeoff_waiting_for_stable_flight))
            return
        }
        if (flightState.enabled || flightState.requested) {
            publish(context.getString(R.string.auto_takeoff_blocked_virtual_stick_not_released))
            return
        }
        val preflight = gate(
            mission = mission,
            requireVirtualStick = false,
            checkPreflight = true,
            allowNotFlying = true,
            allowGroundedPositionUnavailable = true,
        )
        if (!preflight.allowed) {
            publish(context.getString(R.string.auto_takeoff_blocked_detail, preflight.blocks.joinToString()))
            return
        }
        val current = snapshot().toSurveyPoint()
        val awaitingTakeoff = SurveySimulatorExecutionStateMachine(
            mission = mission,
            currentPoint = current,
            returnPoint = currentReturnPoint() ?: current,
        )
        machine = awaitingTakeoff
        val armed = awaitingTakeoff.requestArm(preflight)
        if (armed.state != SurveyExecutionState.ARMING) {
            publish(context.getString(R.string.auto_takeoff_blocked_detail, armed.reason ?: preflight.blocks.joinToString()))
            return
        }
        autoTakeoffPending = true
        autoTakeoffDeadlineElapsedMillis = SystemClock.elapsedRealtime() + AUTO_TAKEOFF_TIMEOUT_MILLIS
        autoTakeoffFlyingSinceElapsedMillis = 0L
        publish(context.getString(R.string.auto_takeoff_waiting_simulator))
        flightPort.takeoff { result ->
            handler.post {
                if (!autoTakeoffPending) return@post
                result.onFailure {
                    cancelAutoTakeoff(context.getString(R.string.dji_auto_takeoff_failed, it.message ?: it.javaClass.simpleName))
                }.onSuccess {
                    handler.removeCallbacks(autoTakeoffTick)
                    handler.post(autoTakeoffTick)
                }
            }
        }
    }

    private fun cancelAutoTakeoff(reason: String) {
        if (!autoTakeoffPending) return
        autoTakeoffPending = false
        autoTakeoffDeadlineElapsedMillis = 0L
        autoTakeoffFlyingSinceElapsedMillis = 0L
        handler.removeCallbacks(autoTakeoffTick)
        if (machine?.status?.state == SurveyExecutionState.ARMING) machine?.abort(reason)
        publish(context.getString(R.string.auto_takeoff_cancelled, reason))
    }

    fun pause(reason: String = context.getString(R.string.user_paused), completion: () -> Unit = {}) {
        pauseInternal(reason, completion)
    }

    fun resume() {
        val currentMission = mission ?: return publish(context.getString(R.string.no_resumable_mission))
        var currentMachine = machine ?: return publish(context.getString(R.string.no_resumable_mission))
        if (currentMachine.status.state != SurveyExecutionState.PAUSED || acquireGuard.pending) return
        val aircraft = snapshot()
        val preAcquireGate = strictResumeGate(currentMission, requireVirtualStick = false)
        if (!preAcquireGate.allowed) {
            publish(context.getString(R.string.resume_blocked_detail, preAcquireGate.blocks.joinToString()))
            return
        }
        restoredCheckpoint?.let { checkpoint ->
            currentMachine = rebuildRestoredMachine(currentMission, checkpoint)
        }
        val acquireToken = acquireGuard.begin(SurveyAcquireRequestGuard.Mode.RESUME)
        publish(context.getString(R.string.reacquiring_virtual_stick))
        flightPort.acquire { result ->
            handler.post {
                if (!acquireGuard.isCurrent(acquireToken) || machine !== currentMachine ||
                    this.mission !== currentMission
                ) return@post
                acquireGuard.consume(acquireToken)
                result.onFailure { publish(context.getString(R.string.resume_failed_detail, it.message ?: it.javaClass.simpleName)) }
                    .onSuccess {
                        val resumed = currentMachine.resume(
                            strictResumeGate(currentMission, requireVirtualStick = true),
                            snapshot().toSurveyPoint(),
                            snapshot().headingDegrees ?: Double.NaN,
                        )
                        if (resumed.state == SurveyExecutionState.RUNNING) {
                            restoredCheckpoint = null
                            beginRunning(currentMission, context.getString(R.string.resumed_returning_to_precise_pause_point))
                        } else {
                            zeroAndRelease(resumed.reason ?: context.getString(R.string.resume_gate_not_passed))
                        }
                    }
            }
        }
    }

    fun restore(
        currentMission: SurveyMission,
        checkpoint: SurveyExecutionCheckpoint,
        restoreBackend: SurveyExecutionBackend,
        restoreUeEndpoint: String,
    ): Result<Unit> = runCatching {
        require(checkpoint.missionId == currentMission.id) { context.getString(R.string.resume_point_mission_mismatch) }
        require(checkpoint.state in CHECKPOINT_ACTIVE_STATES) { context.getString(R.string.resume_point_state_invalid) }
        require(restoreBackend != SurveyExecutionBackend.DJI_KMZ) { context.getString(R.string.kmz_resume_not_custom_route) }
        require(checkpoint.backend == null || checkpoint.backend == restoreBackend) {
            context.getString(
                R.string.resume_backend_mismatch,
                checkpoint.backend?.let { context.getString(it.labelRes) } ?: "-",
                context.getString(restoreBackend.labelRes),
            )
        }
        if (restoreBackend == SurveyExecutionBackend.UE_HIL) {
            SurveyUeBridgeClient.resolvedEndpoint(restoreUeEndpoint, hilPeerHost(), context)
        }
        mission = currentMission
        backend = restoreBackend
        ueEndpoint = restoreUeEndpoint.trim()
        val currentCameraIndex = cameraIndex()
        if (restoreBackend != SurveyExecutionBackend.UE_HIL) {
            require(currentCameraIndex != ComponentIndexType.UNKNOWN) { context.getString(R.string.no_available_camera) }
            cameraController.bind(currentCameraIndex, force = true)
            cameraController.selectPhotoMode()
        }
        bindGimbal(
            currentCameraIndex.takeUnless { it == ComponentIndexType.UNKNOWN }
                ?: ComponentIndexType.LEFT_OR_MAIN,
        )
        resetRuntimeTracking()
        val created = SurveySimulatorExecutionStateMachine(
            currentMission,
            snapshot().toSurveyPoint(),
            currentReturnPoint(),
        )
        // Resolve the durable phase/ordinal against this launch-dependent execution plan before
        // applying recovery policy. In particular, TRACK_ROUTE inserts SURVEY transit legs whose
        // status waypoint still names the previous strip end; looking up that raw waypoint would
        // misclassify the transit as an interrupted capture leg.
        created.restorePausedStable(
            waypointIndex = checkpoint.waypointIndex,
            phase = checkpoint.phase,
            phaseLegOrdinal = checkpoint.phaseLegOrdinal,
            recoveryPoint = null,
        )
        val resolvedExecutionLegIndex = created.executionLegIndex
        val stripStartWaypointIndex = stripStartWaypointIndex(currentMission, checkpoint.waypointIndex)
        val stripStartExecutionLegIndex = stripStartWaypointIndex?.let {
            created.executionLegIndexForMissionWaypoint(it)
        }
        val recovery = SurveyCheckpointRecoveryPolicy.position(
            waypointIndex = checkpoint.waypointIndex,
            executionLegIndex = resolvedExecutionLegIndex,
            state = checkpoint.state,
            phase = created.checkpointPhase,
            targetCaptureAction = created.currentTarget.captureAction,
            hasRecoveryPoint = checkpoint.recoveryPoint != null,
            stripStartWaypointIndex = stripStartWaypointIndex,
            stripStartExecutionLegIndex = stripStartExecutionLegIndex,
        )
        val normalizedPhaseOrdinal = if (recovery.waypointIndex == checkpoint.waypointIndex) {
            created.phaseLegOrdinal()
        } else {
            created.phaseLegOrdinalForMissionWaypoint(recovery.waypointIndex)
        }
        val normalized = checkpoint.copy(
            waypointIndex = recovery.waypointIndex,
            executionLegIndex = recovery.executionLegIndex,
            state = SurveyExecutionState.PAUSED,
            phase = created.checkpointPhase,
            phaseLegOrdinal = normalizedPhaseOrdinal,
        )
        created.restorePausedStable(
            waypointIndex = normalized.waypointIndex,
            phase = normalized.phase,
            phaseLegOrdinal = normalized.phaseLegOrdinal,
            recoveryPoint = normalized.recoveryPoint,
        )
        machine = created
        restoredCheckpoint = normalized
        restoreCaptureState(currentMission, normalized.waypointIndex, normalized.phase)
        publish(context.getString(R.string.resume_point_loaded_manual_resume_required))
    }

    fun checkpoint(): SurveyExecutionCheckpoint? {
        val currentMission = mission ?: return null
        val currentMachine = machine ?: return null
        if (currentMachine.status.state !in CHECKPOINT_ACTIVE_STATES) return null
        val durablePhase = currentMachine.checkpointPhase
        val stripStartWaypointIndex = stripStartWaypointIndex(
            currentMission,
            currentMachine.status.waypointIndex,
        )
        val stripStartExecutionLegIndex = stripStartWaypointIndex?.let {
            currentMachine.executionLegIndexForMissionWaypoint(it)
        }
        val recovery = SurveyCheckpointRecoveryPolicy.position(
            waypointIndex = currentMachine.status.waypointIndex,
            executionLegIndex = currentMachine.executionLegIndex,
            state = currentMachine.status.state,
            phase = durablePhase,
            targetCaptureAction = currentMachine.currentTarget.captureAction,
            hasRecoveryPoint = currentMachine.pausedRecoveryPoint() != null,
            stripStartWaypointIndex = stripStartWaypointIndex,
            stripStartExecutionLegIndex = stripStartExecutionLegIndex,
        )
        val stablePhaseOrdinal = if (recovery.waypointIndex != currentMachine.status.waypointIndex) {
            currentMachine.phaseLegOrdinalForMissionWaypoint(recovery.waypointIndex)
        } else currentMachine.phaseLegOrdinal()
        return SurveyExecutionCheckpoint(
            missionId = currentMission.id,
            waypointIndex = recovery.waypointIndex,
            state = currentMachine.status.state,
            updatedAtEpochMillis = System.currentTimeMillis(),
            executionLegIndex = recovery.executionLegIndex,
            phase = durablePhase,
            recoveryPoint = currentMachine.pausedRecoveryPoint(),
            backend = backend,
            phaseLegOrdinal = stablePhaseOrdinal,
        )
    }

    fun stop(reason: String = context.getString(R.string.user_stopped)) {
        if (autoTakeoffPending) cancelAutoTakeoff(reason)
        invalidateReturnHomeHandoff()
        val cancelledAcquire = acquireGuard.cancel()
        restoredCheckpoint = null
        val currentMachine = machine
        if (currentMachine != null && currentMachine.status.state !in setOf(
                SurveyExecutionState.IDLE,
                SurveyExecutionState.COMPLETED,
                SurveyExecutionState.ABORTED,
            )
        ) currentMachine.abort(reason)
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        captureController.reset()
        pendingCaptureStart = null
        send(BodyVelocityCommand.ZERO)
        if (cancelledAcquire || flightState.enabled || flightState.requested) flightPort.release { }
        publish(reason)
    }

    fun currentSnapshot(): SurveyCustomExecutionSnapshot = latest

    fun preflight(
        mission: SurveyMission,
        allowSimulatorAutoTakeoff: Boolean = false,
    ): SurveyExecutionGateResult = gate(
        mission,
        requireVirtualStick = false,
        checkPreflight = true,
        allowNotFlying = allowSimulatorAutoTakeoff,
        allowGroundedPositionUnavailable = allowSimulatorAutoTakeoff,
        checkCameraGeometry = false,
    )

    private fun pauseInternal(reason: String, completion: () -> Unit = {}) {
        if (autoTakeoffPending) {
            cancelAutoTakeoff(reason)
            completion()
            return
        }
        val currentMachine = machine ?: run {
            completion()
            return
        }
        val cancelledAcquire = acquireGuard.cancel()
        if (currentMachine.status.state !in setOf(
                SurveyExecutionState.RUNNING,
                SurveyExecutionState.ARMING,
            ) && !cancelledAcquire
        ) {
            completion()
            return
        }
        if (currentMachine.status.state in setOf(
                SurveyExecutionState.RUNNING,
                SurveyExecutionState.ARMING,
            )
        ) currentMachine.pause(reason, snapshot().toSurveyPoint())
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        captureController.cancelPendingCapture()
        send(BodyVelocityCommand.ZERO)
        publish(context.getString(R.string.paused_detail, reason))
        if (cancelledAcquire || flightState.enabled || flightState.requested) {
            flightPort.release { handler.post(completion) }
        } else completion()
    }

    private fun abortInternal(reason: String) {
        val cancelledAcquire = acquireGuard.cancel()
        restoredCheckpoint = null
        machine?.abort(reason)
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        captureController.reset()
        pendingCaptureStart = null
        send(BodyVelocityCommand.ZERO)
        if (cancelledAcquire || flightState.enabled || flightState.requested) flightPort.release { }
        publish(context.getString(R.string.aborted_detail, reason))
    }

    private fun finish(message: String) {
        val cancelledAcquire = acquireGuard.cancel()
        restoredCheckpoint = null
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        captureController.reset()
        send(BodyVelocityCommand.ZERO)
        if (cancelledAcquire || flightState.enabled || flightState.requested) flightPort.release { }
        publish(message)
    }

    private fun shouldHandoffToDjiReturnHome(
        currentMission: SurveyMission,
        currentMachine: SurveySimulatorExecutionStateMachine,
    ): Boolean = backend != SurveyExecutionBackend.DJI_KMZ &&
        currentMission.constraints.completionAction == SurveyCompletionAction.RETURN_TO_HOME &&
        currentMachine.status.state == SurveyExecutionState.RUNNING &&
        currentMachine.currentPhase == SurveyExecutionPhase.RETURN_HOME

    private fun handoffToDjiReturnHome(
        currentMachine: SurveySimulatorExecutionStateMachine,
        recoveryPose: SurveyFollowerPose,
    ) {
        if (returnHomeHandoffPending || machine !== currentMachine) return
        returnHomeHandoffPending = true
        val generation = ++returnHomeHandoffGeneration
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        captureController.reset()
        pendingCaptureStart = null
        flightPort.send(BodyVelocityCommand.ZERO).onSuccess { onCommand(BodyVelocityCommand.ZERO) }
        publish(context.getString(R.string.survey_complete_handing_off_rth))
        onEvent(
            "dji_return_home_handoff_started",
            mapOf("backend" to backend.name),
        )

        val startReturnHome = {
            if (isCurrentReturnHomeHandoff(generation, currentMachine)) {
                flightPort.returnHome { result ->
                    handler.post {
                        if (!isCurrentReturnHomeHandoff(generation, currentMachine)) return@post
                        result.onSuccess {
                            returnHomeHandoffPending = false
                            restoredCheckpoint = null
                            currentMachine.acceptDjiReturnHome()
                            publish(context.getString(R.string.mission_succeeded_rth_started))
                        }.onFailure { error ->
                            failDjiReturnHomeHandoff(currentMachine, recoveryPose, error)
                        }
                        onEvent(
                            "dji_return_home_handoff_result",
                            mapOf(
                                "backend" to backend.name,
                                "success" to result.isSuccess,
                                "error" to result.exceptionOrNull()?.message,
                            ),
                        )
                    }
                }
            }
        }
        val releaseRequired = acquireGuard.cancel() || flightState.enabled || flightState.requested
        if (!releaseRequired) {
            startReturnHome()
            return
        }
        flightPort.release { result ->
            handler.post {
                if (!isCurrentReturnHomeHandoff(generation, currentMachine)) return@post
                if (result.isSuccess || (!flightPort.state().enabled && !flightPort.state().requested)) {
                    startReturnHome()
                } else {
                    failDjiReturnHomeHandoff(
                        currentMachine,
                        recoveryPose,
                        result.exceptionOrNull() ?: IllegalStateException(context.getString(R.string.virtual_stick_release_failed)),
                    )
                }
            }
        }
    }

    private fun failDjiReturnHomeHandoff(
        currentMachine: SurveySimulatorExecutionStateMachine,
        recoveryPose: SurveyFollowerPose,
        error: Throwable,
    ) {
        returnHomeHandoffPending = false
        currentMachine.pause(
            context.getString(R.string.dji_rth_start_failed_detail, error.message ?: error.javaClass.simpleName),
            GeoPoint(
                latitude = recoveryPose.latitude,
                longitude = recoveryPose.longitude,
                altitudeMeters = recoveryPose.altitudeMeters,
            ),
        )
        publish(context.getString(R.string.dji_rth_start_failed_mission_incomplete, error.message ?: error.javaClass.simpleName))
    }

    private fun isCurrentReturnHomeHandoff(
        generation: Long,
        currentMachine: SurveySimulatorExecutionStateMachine,
    ): Boolean = !closed && returnHomeHandoffPending &&
        generation == returnHomeHandoffGeneration && machine === currentMachine

    private fun invalidateReturnHomeHandoff() {
        returnHomeHandoffGeneration += 1
        returnHomeHandoffPending = false
    }

    private fun zeroAndRelease(message: String) {
        val cancelledAcquire = acquireGuard.cancel()
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        send(BodyVelocityCommand.ZERO)
        if (cancelledAcquire || flightState.enabled || flightState.requested) flightPort.release { }
        publish(message)
    }

    private fun send(command: BodyVelocityCommand) {
        flightPort.send(command).onSuccess {
            onCommand(command)
        }.onFailure {
            if (machine?.status?.state == SurveyExecutionState.RUNNING) {
                pauseInternal(context.getString(R.string.control_command_send_failed, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun requestCapture(
        position: GeoPoint,
        currentMachine: SurveySimulatorExecutionStateMachine,
        reason: String,
    ) {
        if (photoInFlight) return
        photoInFlight = true
        captureRequestGeneration += 1
        val generation = captureRequestGeneration
        val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
        armCaptureTimeout(generation, position)
        if (backend == SurveyExecutionBackend.UE_HIL) {
            waitForFreshHilFrame(
                generation,
                requestedAtNanos,
                position,
                currentMachine.executionLegIndex,
                currentMachine.status.waypointIndex,
                reason,
            )
            return
        }
        val waypointIndex = currentMachine.status.waypointIndex
        cameraController.takePhoto(onTriggered = { triggeredAtNanos, triggeredAtEpochMillis ->
            onPhotoTriggered(
                SurveyPhotoTrigger(
                    missionId = mission?.id.orEmpty(),
                    backend = backend,
                    passIndex = mission?.waypoints?.getOrNull(waypointIndex)?.passIndex,
                    waypointIndex = waypointIndex,
                    captureView = mission?.waypoints?.getOrNull(waypointIndex)?.captureView?.name,
                    reason = reason,
                    triggeredAtNanos = triggeredAtNanos,
                    triggeredAtEpochMillis = triggeredAtEpochMillis,
                ),
            )
        }) { result ->
            handler.post {
                val capturePosition = snapshot().toSurveyPoint() ?: position
                completeCaptureRequest(
                    generation = generation,
                    position = capturePosition,
                    result = result,
                    failureContext = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    private fun waitForFreshHilFrame(
        generation: Long,
        requestedAtNanos: Long,
        position: GeoPoint,
        executionLegIndex: Int,
        waypointIndex: Int,
        reason: String,
    ) {
        if (generation != captureRequestGeneration || !photoInFlight || closed) return
        val now = SystemClock.elapsedRealtimeNanos()
        val frame = latestHilFrame(HIL_FRAME_MAX_AGE_MILLIS)
            ?.takeIf { it.receivedAndroidMonotonicNanos >= requestedAtNanos }
        if (frame == null) {
            if (now - requestedAtNanos >= CAMERA_ACTION_TIMEOUT_MILLIS * 1_000_000L) {
                completeCaptureRequest(
                    generation,
                    position,
                    Result.failure(IllegalStateException("UE virtual camera frame timeout")),
                    "UE virtual camera frame timeout",
                )
            } else {
                handler.postDelayed({
                    waitForFreshHilFrame(
                        generation,
                        requestedAtNanos,
                        position,
                        executionLegIndex,
                        waypointIndex,
                        reason,
                    )
                }, HIL_FRAME_POLL_MILLIS)
            }
            return
        }
        val extension = if (frame.format == HilFrameProtocol.FORMAT_PNG) "png" else "jpg"
        val mime = if (frame.format == HilFrameProtocol.FORMAT_PNG) "image/png" else "image/jpeg"
        val displayName = "${mission?.id.orEmpty()}_${System.currentTimeMillis()}_${frame.frameId}.$extension"
        val captureAircraft = snapshot()
        val capturePosition = captureAircraft.toSurveyPoint() ?: position
        saveHilCapture(displayName, mime, frame.encoded) { saved ->
            handler.post {
                if (generation != captureRequestGeneration || !photoInFlight || closed) return@post
                saved.onSuccess { savedPath ->
                    onEvent(
                        "capture_image_saved",
                        mapOf(
                            "mission_id" to mission?.id,
                            "backend" to backend?.name,
                            "saved_path" to savedPath,
                            "frame_id" to frame.frameId,
                            "pose_sequence" to frame.poseSequence,
                            "execution_leg_index" to executionLegIndex,
                            "waypoint_index" to waypointIndex,
                        ),
                    )
                    captureSequence += 1
                    val endpoint = activeUeEndpointOrPause() ?: return@onSuccess
                    ueBridge.postCapture(endpoint, SurveyUeCapture(
                        timestampEpochMillis = System.currentTimeMillis(),
                        missionId = mission?.id.orEmpty(),
                        reason = reason,
                        frameId = frame.frameId,
                        poseSequence = frame.poseSequence,
                        frameFormat = extension,
                        width = frame.width,
                        height = frame.height,
                        capturePeerMonotonicNanos = frame.capturePeerMonotonicNanos,
                        savedPath = savedPath,
                        latitude = capturePosition.latitude,
                        longitude = capturePosition.longitude,
                        altitudeAglMeters = capturePosition.altitudeMeters,
                        headingDegrees = captureAircraft.headingDegrees ?: captureAircraft.attitude?.yaw ?: 0.0,
                        gimbalPitchDegrees = captureAircraft.gimbalPitchDegrees ?: 0.0,
                        executionLegIndex = executionLegIndex,
                        waypointIndex = waypointIndex,
                    )) { _, _ -> }
                }
                completeCaptureRequest(
                    generation,
                    capturePosition,
                    saved.map { Unit },
                    saved.exceptionOrNull()?.message,
                )
            }
        }
    }

    private fun completeCaptureRequest(
        generation: Long,
        position: GeoPoint,
        result: Result<Unit>,
        failureContext: String?,
    ) {
        if (generation != captureRequestGeneration || !photoInFlight) return
        captureTimeoutRunnable?.let(handler::removeCallbacks)
        captureTimeoutRunnable = null
        photoInFlight = false
        captureController.onCaptureResult(position, SystemClock.elapsedRealtime(), result.isSuccess)
        val currentMachine = machine
        onEvent(
            "capture_result",
            mapOf(
                "mission_id" to mission?.id,
                "backend" to backend?.name,
                "success" to result.isSuccess,
                "error" to result.exceptionOrNull()?.message,
                "latitude" to position.latitude,
                "longitude" to position.longitude,
                "altitude_m" to position.altitudeMeters,
                "execution_leg_index" to currentMachine?.executionLegIndex,
                "waypoint_index" to currentMachine?.status?.waypointIndex,
            ),
        )
        result.exceptionOrNull()?.let {
            val message = failureContext ?: it.message ?: it.javaClass.simpleName
            if (pendingPointCaptureLeg != null) {
                pendingPointCaptureLeg = null
                pauseInternal(context.getString(R.string.precise_recapture_failed, message))
                return
            }
            if (SurveyRuntimeFaultPolicy.shouldPauseCameraAction(
                    SurveyRuntimeFaultPolicy.ACTION_SURVEY_CAMERA,
                    false,
                    message,
                )
            ) {
                pauseInternal(context.getString(R.string.survey_camera_timeout, message))
            } else publish(context.getString(R.string.survey_camera_failed_retrying, message))
        } ?: pendingPointCaptureLeg?.let { leg ->
            completedPointCaptureLeg = leg
            pendingPointCaptureLeg = null
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun invalidateCaptureRequest() {
        captureRequestGeneration += 1
        photoInFlight = false
        captureTimeoutRunnable?.let(handler::removeCallbacks)
        captureTimeoutRunnable = null
    }

    private fun armCaptureTimeout(generation: Long, fallbackPosition: GeoPoint) {
        captureTimeoutRunnable?.let(handler::removeCallbacks)
        val timeout = Runnable {
            if (generation != captureRequestGeneration || !photoInFlight || closed) return@Runnable
            completeCaptureRequest(
                generation = generation,
                position = snapshot().toSurveyPoint() ?: fallbackPosition,
                result = Result.failure(IllegalStateException("capture pipeline timeout")),
                failureContext = "capture pipeline timeout",
            )
        }
        captureTimeoutRunnable = timeout
        handler.postDelayed(timeout, CAMERA_ACTION_TIMEOUT_MILLIS)
    }

    private fun bindGimbal(index: ComponentIndexType) {
        activeGimbalPort?.stop()
        gimbalState = GimbalState()
        activeGimbalPort = gimbalPortFactory(index).also { port ->
            port.start { state -> handler.post { if (!closed) gimbalState = state } }
        }
    }

    private fun resetRuntimeTracking() {
        handler.removeCallbacks(tick)
        invalidateCaptureRequest()
        captureController.reset()
        lastTargetLeg = -1
        waypointDeadlineElapsedMillis = 0L
        legStartedElapsedMillis = 0L
        gimbalCommandedPitch = Double.NaN
        gimbalSettlingStartedElapsedMillis = 0L
        gimbalLastCommandElapsedMillis = 0L
        gimbalCommandAttempts = 0
        gimbalCommandGeneration += 1L
        gimbalCommandAcceptedElapsedMillis = 0L
        gimbalLimitActive = false
        pendingCaptureStart = null
        pendingPointCaptureLeg = null
        completedPointCaptureLeg = null
    }

    private fun beginRunning(currentMission: SurveyMission, message: String) {
        lastTargetLeg = -1
        machine?.let { setWaypointDeadline(it, snapshot().toFollowerPose(), currentMission) }
        handler.removeCallbacks(tick)
        handler.post(tick)
        publish(message)
    }

    private fun beginTarget(
        target: SurveyWaypoint,
        pose: SurveyFollowerPose,
        currentMission: SurveyMission,
        currentMachine: SurveySimulatorExecutionStateMachine,
    ) {
        setWaypointDeadline(currentMachine, pose, currentMission)
        if (!gimbalCommandedPitch.isFinite() || abs(gimbalCommandedPitch - target.gimbalPitchDegrees) > 0.1) {
            gimbalCommandedPitch = target.gimbalPitchDegrees
            gimbalSettlingStartedElapsedMillis = 0L
            gimbalLastCommandElapsedMillis = 0L
            gimbalCommandAttempts = 0
            gimbalCommandGeneration += 1L
            gimbalCommandAcceptedElapsedMillis = 0L
            gimbalLimitActive = false
        }
    }

    private fun commandGimbal(targetPitchDegrees: Double, nowElapsedMillis: Long) {
        val generation = gimbalCommandGeneration
        gimbalLastCommandElapsedMillis = nowElapsedMillis
        gimbalCommandAttempts += 1
        activeGimbalPort?.rotateToPitch(targetPitchDegrees, 1.0) { result ->
            handler.post {
                if (closed || generation != gimbalCommandGeneration) return@post
                result.onSuccess {
                    gimbalCommandAcceptedElapsedMillis = SystemClock.elapsedRealtime()
                }.onFailure { error ->
                    gimbalCommandAcceptedElapsedMillis = 0L
                    publish(context.getString(R.string.gimbal_command_failed_retrying, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun setWaypointDeadline(
        currentMachine: SurveySimulatorExecutionStateMachine,
        pose: SurveyFollowerPose?,
        currentMission: SurveyMission,
    ) {
        val now = SystemClock.elapsedRealtime()
        legStartedElapsedMillis = now
        val maximumVerticalSpeed = pose?.let {
            verticalSpeedLimit(currentMission, it.altitudeMeters, currentMachine.currentTarget.point.altitudeMeters)
        } ?: currentMission.constraints.takeoffSpeedMetersPerSecond
        val estimate = pose?.let {
            SurveyWaypointFollower.command(
                pose = it,
                target = currentMachine.currentTarget,
                maximumHorizontalSpeedMetersPerSecond = currentMission.constraints
                    .speedForCaptureView(currentMachine.currentTarget.captureView),
                maximumVerticalSpeedMetersPerSecond = maximumVerticalSpeed,
            )
        }
        val horizontalSeconds = (estimate?.horizontalErrorMeters ?: 0.0) /
            currentMission.constraints.speedForCaptureView(currentMachine.currentTarget.captureView)
                .coerceAtLeast(0.1)
        val verticalSeconds = abs(estimate?.verticalErrorMeters ?: 0.0) /
            maximumVerticalSpeed.coerceAtLeast(0.1)
        val allowanceMillis = (
            maxOf(30.0, horizontalSeconds * 4.0 + verticalSeconds * 2.0 + 15.0) * 1_000.0
            ).toLong()
        waypointDeadlineElapsedMillis = now + allowanceMillis
    }

    private fun verticalSpeedLimit(
        mission: SurveyMission,
        currentAltitudeMeters: Double,
        targetAltitudeMeters: Double,
    ): Double = if (targetAltitudeMeters < currentAltitudeMeters - 0.05) {
        mission.constraints.descentSpeedMetersPerSecond
    } else {
        mission.constraints.takeoffSpeedMetersPerSecond
    }

    private fun restoreCaptureState(
        currentMission: SurveyMission,
        waypointIndex: Int,
        phase: SurveyExecutionPhase,
    ) {
        captureController.configure(
            currentMission.constraints.captureTriggerMode,
            currentMission.constraints.timedCaptureIntervalSeconds,
        )
        if (phase != SurveyExecutionPhase.SURVEY) return
        var activeInterval: Double? = null
        currentMission.waypoints.take(waypointIndex).forEach { waypoint ->
            when (waypoint.captureAction) {
                CaptureAction.START_DISTANCE_INTERVAL -> activeInterval = waypoint.captureIntervalMeters
                CaptureAction.STOP_DISTANCE_INTERVAL -> activeInterval = null
                CaptureAction.CAPTURE_ON_REACH -> Unit
                CaptureAction.NONE -> Unit
            }
        }
        activeInterval?.let {
            captureController.restoreActive(
                captureIntervalMeters = it,
                mode = currentMission.constraints.captureTriggerMode,
                timedCaptureIntervalSeconds = currentMission.constraints.timedCaptureIntervalSeconds,
            )
        }
    }

    private fun captureReady(): Boolean = !photoInFlight && when (backend) {
        SurveyExecutionBackend.UE_HIL -> latestHilFrame(HIL_FRAME_MAX_AGE_MILLIS) != null
        SurveyExecutionBackend.CUSTOM_VIRTUAL_STICK -> cameraReady()
        SurveyExecutionBackend.DJI_KMZ -> false
    }

    private fun scheduleTick() {
        handler.postDelayed(tick, VirtualStickCadence.PERIOD_MILLIS)
    }

    private fun currentReturnPoint(): GeoPoint? {
        val aircraft = snapshot()
        return aircraft.homeLocation?.let { GeoPoint(it.latitude, it.longitude, RETURN_HOVER_ALTITUDE_METERS) }
            ?: aircraft.aircraftLocation?.let {
                GeoPoint(it.latitude, it.longitude, RETURN_HOVER_ALTITUDE_METERS)
            }
    }

    private fun postTelemetry(currentMachine: SurveySimulatorExecutionStateMachine, aircraft: AircraftSnapshot) {
        if (backend != SurveyExecutionBackend.UE_HIL) return
        val location = aircraft.aircraftLocation ?: return
        val endpoint = activeUeEndpointOrPause() ?: return
        ueBridge.postTelemetry(endpoint, SurveyUeTelemetry(
            timestampEpochMillis = System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeAglMeters = aircraft.relativeAltitudeMeters ?: location.altitudeMeters ?: 0.0,
            headingDegrees = aircraft.headingDegrees ?: aircraft.attitude?.yaw ?: 0.0,
            gimbalPitchDegrees = aircraft.gimbalPitchDegrees ?: 0.0,
            simulatorActive = aircraft.simulatorActive,
            simulatorFlying = aircraft.isFlying,
            executionState = currentMachine.status.state,
            waypointIndex = currentMachine.status.waypointIndex,
        )) { _, _ -> }
    }

    private fun postTarget(currentMission: SurveyMission, currentMachine: SurveySimulatorExecutionStateMachine) {
        if (backend != SurveyExecutionBackend.UE_HIL) return
        val endpoint = activeUeEndpointOrPause() ?: return
        ueBridge.postTarget(endpoint, SurveyUeTarget(
            timestampEpochMillis = System.currentTimeMillis(),
            missionId = currentMission.id,
            executionState = currentMachine.status.state,
            phase = currentMachine.currentPhase,
            executionLegIndex = currentMachine.executionLegIndex,
            waypointIndex = currentMachine.status.waypointIndex,
            waypoint = currentMachine.currentTarget,
        )) { _, _ -> }
    }

    private fun activeUeEndpoint(): String =
        SurveyUeBridgeClient.resolvedEndpoint(ueEndpoint, hilPeerHost(), context)

    private fun activeUeEndpointOrPause(): String? = runCatching(::activeUeEndpoint)
        .getOrElse {
            if (machine?.status?.state in setOf(SurveyExecutionState.ARMING, SurveyExecutionState.RUNNING)) {
                pauseInternal(context.getString(R.string.ue_hil_peer_unavailable_detail, it.message ?: it.javaClass.simpleName))
            } else {
                publish(context.getString(R.string.ue_hil_peer_unavailable_detail, it.message ?: it.javaClass.simpleName))
            }
            null
        }

    private fun publish(message: String) {
        val currentMachine = machine
        val durableStatus = currentMachine?.status
            ?: SurveyExecutionStatus(SurveyExecutionState.IDLE, 0, null)
        val visibleStatus = if (acquireGuard.resuming &&
            durableStatus.state == SurveyExecutionState.PAUSED
        ) {
            durableStatus.copy(
                state = SurveyExecutionState.ARMING,
                reason = "resuming: Virtual Stick acquire pending",
            )
        } else durableStatus
        val estimate = currentMachine?.remainingEstimate(
            currentPosition = snapshot().toSurveyPoint(),
            currentHeadingDegrees = snapshot().headingDegrees ?: Double.NaN,
            currentHorizontalSpeedMetersPerSecond = snapshot().velocity?.let { hypot(it.north, it.east) }
                ?: Double.NaN,
            currentVerticalSpeedMetersPerSecond = snapshot().velocity?.up ?: Double.NaN,
        ) ?: SurveyRemainingEstimate(0.0, 0.0)
        latest = SurveyCustomExecutionSnapshot(
            status = visibleStatus,
            phase = currentMachine?.currentPhase,
            executionLegIndex = currentMachine?.executionLegIndex ?: 0,
            executionLegCount = currentMachine?.executionLegCount ?: 0,
            currentSectionSeconds = estimate.currentSectionSeconds,
            totalRemainingSeconds = estimate.totalSeconds,
            currentTarget = currentMachine?.currentTarget?.point,
            recoveryPoint = currentMachine?.pausedRecoveryPoint(),
            message = message,
            controlAcquirePending = acquireGuard.pending,
        )
        onSnapshot(latest)
    }

    private fun strictResumeGate(
        mission: SurveyMission,
        requireVirtualStick: Boolean,
    ): SurveyExecutionGateResult {
        val preflight = gate(
            mission = mission,
            requireVirtualStick = requireVirtualStick,
            checkPreflight = true,
        )
        val runtime = gate(
            mission = mission,
            requireVirtualStick = requireVirtualStick,
            checkPreflight = false,
        )
        val blocks = linkedSetOf<SurveyExecutionBlock>().apply {
            addAll(preflight.blocks)
            addAll(runtime.blocks)
        }
        return SurveyExecutionGateResult(
            allowed = blocks.isEmpty(),
            blocks = blocks,
            startDistanceMeters = preflight.startDistanceMeters,
        )
    }

    private fun rebuildRestoredMachine(
        currentMission: SurveyMission,
        checkpoint: SurveyExecutionCheckpoint,
    ): SurveySimulatorExecutionStateMachine {
        val rebuilt = SurveySimulatorExecutionStateMachine(
            currentMission,
            snapshot().toSurveyPoint(),
            currentReturnPoint(),
        )
        rebuilt.restorePausedStable(
            waypointIndex = checkpoint.waypointIndex,
            phase = checkpoint.phase,
            phaseLegOrdinal = checkpoint.phaseLegOrdinal,
            recoveryPoint = checkpoint.recoveryPoint,
        )
        machine = rebuilt
        restoreCaptureState(currentMission, checkpoint.waypointIndex, checkpoint.phase)
        publish(context.getString(R.string.resume_point_revalidated))
        return rebuilt
    }

    private fun stripStartWaypointIndex(
        currentMission: SurveyMission,
        waypointIndex: Int,
    ): Int? {
        val target = currentMission.waypoints.getOrNull(waypointIndex) ?: return null
        if (target.captureAction != CaptureAction.STOP_DISTANCE_INTERVAL) return null
        return currentMission.waypoints.indices.reversed().firstOrNull { index ->
            index < waypointIndex &&
                currentMission.waypoints[index].passIndex == target.passIndex &&
                currentMission.waypoints[index].captureAction == CaptureAction.START_DISTANCE_INTERVAL
        }
    }

    private fun gate(
        mission: SurveyMission,
        requireVirtualStick: Boolean,
        checkPreflight: Boolean,
        allowNotFlying: Boolean = false,
        allowGroundedPositionUnavailable: Boolean = false,
        checkCameraGeometry: Boolean = true,
    ): SurveyExecutionGateResult {
        val aircraft = snapshot()
        val location = aircraft.aircraftLocation
        val nowEpoch = System.currentTimeMillis()
        val telemetryUpdatedAtNanos = maxOf(
            aircraft.aircraftLocationUpdatedAtNanos,
            aircraft.flightStateUpdatedAtNanos,
            aircraft.updatedAtNanos,
        )
        val ageMillis = if (telemetryUpdatedAtNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - telemetryUpdatedAtNanos).coerceAtLeast(0L) / 1_000_000L)
        } else Long.MAX_VALUE
        val velocity = aircraft.velocity
        val result = SurveySimulatorGate.evaluate(
            mission = mission,
            telemetry = SurveyExecutionTelemetry(
                connected = aircraft.connected,
                simulatorActive = aircraft.simulatorActive,
                simulatorFlying = aircraft.isFlying,
                virtualStickEnabled = flightState.enabled && flightState.owner == ControlOwner.APP,
                // RC owns the aircraft normally before Virtual Stick is acquired. That state is
                // not manual takeover; only debounced physical stick movement is a preflight block.
                sticksActive = aircraft.sticksActive,
                latitude = location?.latitude ?: Double.NaN,
                longitude = location?.longitude ?: Double.NaN,
                altitudeMeters = aircraft.relativeAltitudeMeters ?: location?.altitudeMeters ?: Double.NaN,
                updatedAtEpochMillis = nowEpoch - ageMillis.coerceAtMost(nowEpoch),
                aircraftFlying = aircraft.isFlying,
                batteryPercent = aircraft.aircraftBatteryPercent ?: -1,
                rcBatteryPercent = aircraft.remoteControllerBatteryPercent ?: -1,
                rcSignalPercent = aircraft.remoteControllerSignalPercent ?: -1,
                satelliteCount = aircraft.gpsSatelliteCount ?: -1,
                gpsSignalUsable = location != null && aircraft.gpsSignalLevel in setOf("LEVEL_4", "LEVEL_5"),
                homeLocationValid = aircraft.homeLocation != null,
                homeLatitude = aircraft.homeLocation?.latitude ?: Double.NaN,
                homeLongitude = aircraft.homeLocation?.longitude ?: Double.NaN,
                goHomeHeightMeters = aircraft.goHomeHeightMeters ?: 0,
                maxFlightHeightMeters = aircraft.maxFlightHeightMeters ?: 0,
                maxFlightRadiusMeters = aircraft.maxFlightRadiusMeters ?: 0,
                maxFlightRadiusEnabled = aircraft.maxFlightRadiusEnabled == true,
                horizontalSpeedMetersPerSecond = velocity?.let { hypot(it.north, it.east) } ?: 0.0,
                verticalSpeedMetersPerSecond = velocity?.up ?: 0.0,
                goingHome = aircraft.flightMode?.contains("GO_HOME", ignoreCase = true) == true,
                landing = aircraft.flightMode?.contains("LAND", ignoreCase = true) == true,
            ),
            nowEpochMillis = nowEpoch,
            requireVirtualStick = requireVirtualStick,
            allowNotFlying = allowNotFlying,
            allowGroundedPositionUnavailable = allowGroundedPositionUnavailable,
            environment = if (aircraft.simulatorActive) {
                SurveyExecutionEnvironment.DJI_SIMULATOR
            } else SurveyExecutionEnvironment.REAL_AIRCRAFT_MANUAL_TAKEOFF,
            checkPreflightReadiness = checkPreflight,
        )
        if (!checkCameraGeometry || backend == SurveyExecutionBackend.UE_HIL) return result
        return SurveyCameraExecutionPolicy.evaluate(
            result, cameraController.currentSnapshot().connected, cameraGeometryMatches(mission),
        ).gate
    }

    private fun cameraReady(): Boolean = cameraController.currentSnapshot().let {
        it.connected && it.mode.isPhotoMode && !it.busy && it.storageState == "INSERTED"
    }

    private fun AircraftSnapshot.toFollowerPose(): SurveyFollowerPose? {
        val location = aircraftLocation ?: return null
        val altitude = relativeAltitudeMeters ?: location.altitudeMeters ?: return null
        val heading = headingDegrees ?: attitude?.yaw ?: return null
        return SurveyFollowerPose(location.latitude, location.longitude, altitude, heading)
    }

    private fun AircraftSnapshot.toSurveyPoint(): GeoPoint? {
        val location = aircraftLocation ?: return null
        return GeoPoint(
            location.latitude,
            location.longitude,
            relativeAltitudeMeters ?: location.altitudeMeters ?: 0.0,
        )
    }

    override fun close() {
        if (closed) return
        val cancelledAcquire = acquireGuard.cancel()
        val currentMachine = machine
        if (currentMachine?.status?.state in setOf(
                SurveyExecutionState.RUNNING,
                SurveyExecutionState.ARMING,
            )
        ) {
            currentMachine?.pause(context.getString(R.string.page_closed), snapshot().toSurveyPoint())
            publish(context.getString(R.string.paused_detail, context.getString(R.string.page_closed)))
        }
        send(BodyVelocityCommand.ZERO)
        if (cancelledAcquire || flightState.enabled || flightState.requested) flightPort.release { }
        flightPort.stop()
        closed = true
        handler.removeCallbacksAndMessages(null)
        invalidateCaptureRequest()
        cameraController.destroy()
        activeGimbalPort?.stop()
        activeGimbalPort = null
        ueBridge.close()
    }

    private companion object {
        const val CAMERA_ACTION_TIMEOUT_MILLIS = 8_000L
        const val HIL_FRAME_MAX_AGE_MILLIS = 1_500L
        const val HIL_FRAME_POLL_MILLIS = 50L
        const val RETURN_HOVER_ALTITUDE_METERS = 1.2
        const val AUTO_TAKEOFF_TIMEOUT_MILLIS = 30_000L
        const val AUTO_TAKEOFF_STABLE_MILLIS = 1_000L
        const val AUTO_TAKEOFF_POLL_MILLIS = 200L
        val CHECKPOINT_ACTIVE_STATES = setOf(
            SurveyExecutionState.ARMING,
            SurveyExecutionState.RUNNING,
            SurveyExecutionState.PAUSED,
        )
    }
}
