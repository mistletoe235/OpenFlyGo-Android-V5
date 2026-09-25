package edu.playground.djivln.adapter.dji

import android.content.Context
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointActionListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.BreakPointInfo
import dji.v5.manager.aircraft.waypoint3.model.RecoverActionType
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import dji.v5.manager.interfaces.IWaypointMissionManager
import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.domain.wayline.WaylineActionEvent
import edu.playground.djivln.domain.wayline.WaylineCompletion
import edu.playground.djivln.domain.wayline.WaylineMissionName
import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.domain.wayline.WaylinePort
import edu.playground.djivln.domain.wayline.WaylineState
import edu.playground.djivln.domain.wayline.WaylineStateListener
import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText
import java.io.File

internal interface DjiWaylineClient {
    fun start(
        onPhase: (WaylinePhase, String) -> Unit,
        onExecutingInfo: (String?, Int?, Int?) -> Unit,
        onInterrupt: (String) -> Unit,
        onAction: (WaylineActionEvent) -> Unit,
    )

    fun stop()
    fun upload(kmzPath: String, onProgress: (Double) -> Unit, completion: (Result<Unit>) -> Unit)
    fun availableWaylineIds(missionFileName: String): List<Int>
    fun execute(missionFileName: String, waylineIds: List<Int>, completion: (Result<Unit>) -> Unit)
    fun executeFromBreakpoint(
        missionFileName: String,
        breakpoint: WaylineBreakpoint,
        completion: (Result<Unit>) -> Unit,
    )
    fun pause(completion: (Result<Unit>) -> Unit)
    fun resume(breakpoint: WaylineBreakpoint?, completion: (Result<Unit>) -> Unit)
    fun queryBreakpoint(missionFileName: String, completion: (Result<WaylineBreakpoint?>) -> Unit)
    fun stopMission(missionFileName: String, completion: (Result<Unit>) -> Unit)
}

class DjiV5WaylinePort internal constructor(
    private val client: DjiWaylineClient,
    context: Context? = null,
) : WaylinePort {
    private val appContext = context?.applicationContext
    constructor(context: Context? = null) : this(DjiSdkWaylineClient(), context)

    private var listener: WaylineStateListener? = null
    private var current = WaylineState()
    private var started = false
    private var breakpointQueryGeneration = 0L
    private var pauseGeneration = 0L
    private var pauseAccepted = false
    private val pendingPauseCompletions = mutableListOf<WaylineCompletion>()

    override fun start(listener: WaylineStateListener) {
        this.listener = listener
        if (!started) {
            started = true
            client.start(
                onPhase = { phase, message ->
                    val previousPhase = current.phase
                    val staleRunningState = pauseAccepted && phase in setOf(
                        WaylinePhase.PREPARING, WaylinePhase.RECOVERING, WaylinePhase.EXECUTING,
                    )
                    if (!staleRunningState &&
                        (phase != WaylinePhase.PAUSED || current.phase.acceptsInterruption())
                    ) {
                        update {
                            it.copy(
                                phase = phase,
                                message = UiText.resource(R.string.wayline_dji_state, message),
                                error = if (phase in setOf(
                                        WaylinePhase.PREPARING,
                                        WaylinePhase.EXECUTING,
                                        WaylinePhase.READY,
                                    )
                                ) null else it.error,
                            )
                        }
                        if (phase == WaylinePhase.PAUSED ||
                            (phase == WaylinePhase.FINISHED && previousPhase.acceptsInterruption())
                        ) refreshCurrentBreakpoint()
                    }
                },
                onExecutingInfo = { missionName, waylineId, waypointIndex ->
                    val normalizedMissionName = WaylineMissionName.normalizeOrNull(missionName)
                    update {
                        val fallbackBreakpoint = if (waylineId != null && waylineId >= 0 &&
                            waypointIndex != null && waypointIndex >= 0
                        ) {
                            it.breakpoint?.takeIf { existing ->
                                existing.waylineId == waylineId && existing.waypointId == waypointIndex
                            } ?: WaylineBreakpoint(
                                waylineId = waylineId,
                                waypointId = waypointIndex,
                                segmentProgress = 0.0,
                            )
                        } else it.breakpoint
                        it.copy(
                            // The V5 simulator can continue reporting PREPARING after publishing
                            // a valid executing waypoint. Executing info is the stronger signal.
                            phase = if (
                                it.phase == WaylinePhase.PREPARING &&
                                waylineId != null && waylineId >= 0 &&
                                waypointIndex != null && waypointIndex >= 0
                            ) WaylinePhase.EXECUTING else it.phase,
                            missionFileName = normalizedMissionName ?: it.missionFileName,
                            waylineId = waylineId,
                            waypointIndex = waypointIndex,
                            breakpoint = fallbackBreakpoint,
                        )
                    }
                },
                onInterrupt = { error ->
                    if (current.phase.acceptsInterruption()) {
                        update {
                            it.copy(
                                phase = WaylinePhase.PAUSED,
                                message = UiText.resource(R.string.wayline_dji_interrupted),
                                error = error,
                            )
                        }
                        refreshCurrentBreakpoint()
                    }
                },
                onAction = { action -> update { it.copy(actionEvent = action) } },
            )
        }
        listener.onStateChanged(current)
    }

    override fun stop() {
        invalidatePause()
        if (started) client.stop()
        breakpointQueryGeneration += 1
        started = false
        listener = null
    }

    override fun state(): WaylineState = current

    override fun confirmExecutionFromTelemetry(): Boolean {
        if (current.phase !in setOf(WaylinePhase.PREPARING, WaylinePhase.RECOVERING)) return false
        update {
            it.copy(
                phase = WaylinePhase.EXECUTING,
                message = UiText.resource(R.string.wayline_executing),
                error = null,
            )
        }
        return true
    }

    override fun availableWaylineIds(missionFileName: String): List<Int> =
        client.availableWaylineIds(WaylineMissionName.normalize(missionFileName))

    override fun upload(kmzPath: String, completion: WaylineCompletion) {
        val file = File(kmzPath)
        if (!file.isFile) {
            completeFailure(
                IllegalArgumentException(text(R.string.wayline_kmz_file_missing, "KMZ file does not exist: $kmzPath", kmzPath)),
                completion,
            )
            return
        }
        invalidatePause()
        update {
            WaylineState(
                WaylinePhase.UPLOADING,
                WaylineMissionName.normalize(file.name),
                message = UiText.resource(R.string.wayline_uploading_kmz),
            )
        }
        client.upload(
            kmzPath,
            onProgress = { progress -> update { it.copy(uploadProgress = progress.coerceIn(0.0, 1.0)) } },
        ) { result ->
            result.onSuccess {
                update {
                    it.copy(
                        phase = WaylinePhase.READY,
                        uploadProgress = 1.0,
                        message = UiText.resource(R.string.wayline_kmz_uploaded),
                        error = null,
                    )
                }
                completion.complete(Result.success(Unit))
            }.onFailure { completeFailure(it, completion) }
        }
    }

    override fun execute(missionFileName: String, waylineIds: List<Int>, completion: WaylineCompletion) {
        val normalizedMissionName = WaylineMissionName.normalize(missionFileName)
        val availableIds = client.availableWaylineIds(normalizedMissionName)
        val unavailableIds = if (availableIds.isEmpty()) emptyList() else waylineIds.filterNot(availableIds::contains)
        if (unavailableIds.isNotEmpty()) {
            completeFailure(
                IllegalArgumentException(
                    text(
                        R.string.wayline_ids_unavailable,
                        "Wayline IDs do not exist: $unavailableIds; available IDs: $availableIds",
                        unavailableIds,
                        availableIds,
                    ),
                ),
                completion,
            )
            return
        }
        invalidatePause()
        update {
            it.copy(
                phase = WaylinePhase.PREPARING,
                missionFileName = normalizedMissionName,
                waylineId = null,
                waypointIndex = null,
                message = UiText.resource(R.string.wayline_starting),
                breakpoint = null,
                error = null,
            )
        }
        client.execute(normalizedMissionName, waylineIds) { complete(it, completion) }
    }

    override fun executeFromBreakpoint(
        missionFileName: String,
        breakpoint: WaylineBreakpoint,
        completion: WaylineCompletion,
    ) {
        val normalizedMissionName = WaylineMissionName.normalize(missionFileName)
        val availableIds = client.availableWaylineIds(normalizedMissionName)
        if (availableIds.isNotEmpty() && breakpoint.waylineId !in availableIds) {
            completeFailure(
                IllegalArgumentException(
                    text(
                        R.string.wayline_breakpoint_id_unavailable,
                        "Breakpoint wayline ID ${breakpoint.waylineId} is unavailable; available IDs: $availableIds",
                        breakpoint.waylineId,
                        availableIds,
                    ),
                ),
                completion,
            )
            return
        }
        invalidatePause()
        update {
            it.copy(
                phase = WaylinePhase.RECOVERING,
                missionFileName = normalizedMissionName,
                waylineId = breakpoint.waylineId,
                waypointIndex = breakpoint.waypointId,
                breakpoint = breakpoint,
                message = UiText.resource(R.string.wayline_resuming_from_dji_breakpoint),
            )
        }
        client.executeFromBreakpoint(normalizedMissionName, breakpoint) { complete(it, completion) }
    }

    override fun pause(completion: WaylineCompletion) {
        if (current.phase == WaylinePhase.PAUSED) {
            completion.complete(Result.success(Unit))
            return
        }
        pendingPauseCompletions.add(completion)
        if (pendingPauseCompletions.size > 1) return
        val generation = ++pauseGeneration
        client.pause { result ->
            if (generation != pauseGeneration) return@pause
            val completions = pendingPauseCompletions.toList()
            pendingPauseCompletions.clear()
            if (result.isSuccess && current.phase.acceptsInterruption()) {
                pauseAccepted = true
                update {
                    it.copy(
                        phase = WaylinePhase.PAUSED,
                        message = UiText.resource(R.string.wayline_pause_accepted),
                        error = null,
                    )
                }
            }
            completions.forEach { it.complete(result) }
        }
    }

    private fun invalidatePause() {
        pauseGeneration += 1
        pauseAccepted = false
        val completions = pendingPauseCompletions.toList()
        pendingPauseCompletions.clear()
        completions.forEach {
            it.complete(Result.failure(IllegalStateException("Pause superseded by another mission operation")))
        }
    }

    private fun text(resourceId: Int, fallback: String, vararg arguments: Any): String =
        appContext?.getString(resourceId, *arguments) ?: fallback

    override fun resume(completion: WaylineCompletion) = resumeInternal(null, completion)

    override fun resume(breakpoint: WaylineBreakpoint, completion: WaylineCompletion) =
        resumeInternal(breakpoint, completion)

    private fun resumeInternal(breakpoint: WaylineBreakpoint?, completion: WaylineCompletion) {
        invalidatePause()
        update {
            it.copy(
                phase = WaylinePhase.RECOVERING,
                breakpoint = breakpoint ?: it.breakpoint,
                message = UiText.resource(R.string.wayline_restoring_dji),
                error = null,
            )
        }
        client.resume(breakpoint) { complete(it, completion) }
    }

    override fun queryBreakpoint(
        missionFileName: String,
        completion: edu.playground.djivln.domain.wayline.WaylineBreakpointCompletion,
    ) {
        val normalizedMissionName = WaylineMissionName.normalize(missionFileName)
        val generation = ++breakpointQueryGeneration
        client.queryBreakpoint(normalizedMissionName) { result ->
            result.onSuccess { breakpoint ->
                if (generation == breakpointQueryGeneration && breakpoint != null &&
                    WaylineMissionName.matches(current.missionFileName, normalizedMissionName)
                ) {
                    update {
                        it.copy(
                            missionFileName = normalizedMissionName,
                            waylineId = breakpoint.waylineId,
                            waypointIndex = breakpoint.waypointId,
                            breakpoint = breakpoint,
                        )
                    }
                }
            }
            completion.complete(result)
        }
    }

    override fun stopMission(missionFileName: String, completion: WaylineCompletion) {
        invalidatePause()
        breakpointQueryGeneration += 1
        client.stopMission(WaylineMissionName.normalize(missionFileName)) { complete(it, completion) }
    }

    private fun refreshCurrentBreakpoint() {
        val missionFileName = current.missionFileName ?: return
        queryBreakpoint(missionFileName) { }
    }

    private fun complete(result: Result<Unit>, completion: WaylineCompletion) {
        result.onFailure { error ->
            update {
                it.copy(
                    phase = WaylinePhase.ERROR,
                    message = UiText.external(error.message ?: error.javaClass.simpleName),
                    error = error.message,
                )
            }
        }
        completion.complete(result)
    }

    private fun completeFailure(error: Throwable, completion: WaylineCompletion) {
        update {
            it.copy(
                phase = WaylinePhase.ERROR,
                message = UiText.external(error.message ?: error.javaClass.simpleName),
                error = error.message,
            )
        }
        completion.complete(Result.failure(error))
    }

    private fun update(reducer: (WaylineState) -> WaylineState) {
        current = reducer(current)
        listener?.onStateChanged(current)
    }

    private fun WaylinePhase.acceptsInterruption(): Boolean = when (this) {
        WaylinePhase.PREPARING,
        WaylinePhase.EXECUTING,
        WaylinePhase.RECOVERING,
        WaylinePhase.PAUSED,
        -> true
        else -> false
    }

}

private class DjiSdkWaylineClient(
    private val manager: IWaypointMissionManager = WaypointMissionManager.getInstance(),
) : DjiWaylineClient {
    private var missionStateListener: WaypointMissionExecuteStateListener? = null
    private var executingInfoListener: WaylineExecutingInfoListener? = null
    private var actionListener: WaypointActionListener? = null

    override fun start(
        onPhase: (WaylinePhase, String) -> Unit,
        onExecutingInfo: (String?, Int?, Int?) -> Unit,
        onInterrupt: (String) -> Unit,
        onAction: (WaylineActionEvent) -> Unit,
    ) {
        missionStateListener = WaypointMissionExecuteStateListener { state ->
            onPhase(state.toPhase(), state.name)
        }.also(manager::addWaypointMissionExecuteStateListener)
        executingInfoListener = object : WaylineExecutingInfoListener {
            override fun onWaylineExecutingInfoUpdate(info: dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo) {
                onExecutingInfo(info.missionFileName, info.waylineID, info.currentWaypointIndex)
            }

            override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError) {
                onInterrupt(error.toString())
            }
        }.also(manager::addWaylineExecutingInfoListener)
        actionListener = object : WaypointActionListener {
            override fun onExecutionStart(actionId: Int) {
                onAction(WaylineActionEvent(actionId = actionId, started = true))
            }

            override fun onExecutionFinish(actionId: Int, error: IDJIError?) {
                onAction(WaylineActionEvent(actionId = actionId, started = false, error = error?.toString()))
            }

            override fun onExecutionStart(actionGroupId: Int, actionId: Int) {
                onAction(WaylineActionEvent(actionGroupId, actionId, started = true))
            }

            override fun onExecutionFinish(actionGroupId: Int, actionId: Int, error: IDJIError?) {
                onAction(WaylineActionEvent(actionGroupId, actionId, started = false, error = error?.toString()))
            }
        }.also(manager::addWaypointActionListener)
    }

    override fun stop() {
        missionStateListener?.let(manager::removeWaypointMissionExecuteStateListener)
        executingInfoListener?.let(manager::removeWaylineExecutingInfoListener)
        actionListener?.let(manager::removeWaypointActionListener)
        missionStateListener = null
        executingInfoListener = null
        actionListener = null
    }

    override fun upload(kmzPath: String, onProgress: (Double) -> Unit, completion: (Result<Unit>) -> Unit) {
        manager.pushKMZFileToAircraft(kmzPath, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double) = onProgress(progress)
            override fun onSuccess() = completion(Result.success(Unit))
            override fun onFailure(error: IDJIError) = completion(Result.failure(DjiOperationException(error)))
        })
    }

    override fun availableWaylineIds(missionFileName: String): List<Int> =
        runCatching { manager.getAvailableWaylineIDs(missionFileName).orEmpty() }.getOrDefault(emptyList())

    override fun execute(missionFileName: String, waylineIds: List<Int>, completion: (Result<Unit>) -> Unit) {
        val callback = callback(completion)
        if (waylineIds.isEmpty()) manager.startMission(missionFileName, callback)
        else manager.startMission(missionFileName, waylineIds, callback)
    }

    override fun executeFromBreakpoint(
        missionFileName: String,
        breakpoint: WaylineBreakpoint,
        completion: (Result<Unit>) -> Unit,
    ) = manager.startMission(missionFileName, breakpoint.toDji(), callback(completion))

    override fun pause(completion: (Result<Unit>) -> Unit) = manager.pauseMission(callback(completion))

    override fun resume(breakpoint: WaylineBreakpoint?, completion: (Result<Unit>) -> Unit) {
        if (breakpoint == null) manager.resumeMission(callback(completion))
        else manager.resumeMission(breakpoint.toDji(), callback(completion))
    }

    override fun queryBreakpoint(missionFileName: String, completion: (Result<WaylineBreakpoint?>) -> Unit) {
        manager.queryBreakPointInfoFromAircraft(
            missionFileName,
            object : CommonCallbacks.CompletionCallbackWithParam<BreakPointInfo> {
                override fun onSuccess(value: BreakPointInfo?) = completion(Result.success(value?.toDomain()))
                override fun onFailure(error: IDJIError) = completion(Result.failure(DjiOperationException(error)))
            },
        )
    }

    override fun stopMission(missionFileName: String, completion: (Result<Unit>) -> Unit) =
        manager.stopMission(missionFileName, callback(completion))

    private fun callback(completion: (Result<Unit>) -> Unit) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = completion(Result.success(Unit))
        override fun onFailure(error: IDJIError) = completion(Result.failure(DjiOperationException(error)))
    }

    private fun BreakPointInfo.toDomain(): WaylineBreakpoint? {
        val wayline = waylineID ?: return null
        val waypoint = waypointID ?: return null
        val progress = segmentProgress ?: return null
        val point = location
        return runCatching {
            WaylineBreakpoint(
                waylineId = wayline,
                waypointId = waypoint,
                segmentProgress = progress.coerceIn(0.0, 1.0),
                latitude = point?.latitude,
                longitude = point?.longitude,
                altitudeMeters = point?.altitude,
                recoverActionType = recoverActionType?.name,
            )
        }.getOrNull()
    }

    private fun WaylineBreakpoint.toDji(): BreakPointInfo {
        val result = BreakPointInfo(waylineId, waypointId, segmentProgress)
        if (latitude != null && longitude != null && altitudeMeters != null) {
            result.location = LocationCoordinate3D(latitude, longitude, altitudeMeters)
        }
        recoverActionType?.let { name ->
            runCatching { RecoverActionType.valueOf(name) }.getOrNull()?.let { result.recoverActionType = it }
        }
        return result
    }

    private fun WaypointMissionExecuteState.toPhase(): WaylinePhase = when (this) {
        WaypointMissionExecuteState.DISCONNECTED -> WaylinePhase.DISCONNECTED
        WaypointMissionExecuteState.NOT_SUPPORTED -> WaylinePhase.UNSUPPORTED
        WaypointMissionExecuteState.IDLE -> WaylinePhase.IDLE
        WaypointMissionExecuteState.READY -> WaylinePhase.READY
        WaypointMissionExecuteState.UPLOADING -> WaylinePhase.UPLOADING
        WaypointMissionExecuteState.PREPARING, WaypointMissionExecuteState.ENTER_WAYLINE -> WaylinePhase.PREPARING
        WaypointMissionExecuteState.EXECUTING, WaypointMissionExecuteState.RETURN_TO_START_POINT -> WaylinePhase.EXECUTING
        WaypointMissionExecuteState.INTERRUPTED -> WaylinePhase.PAUSED
        WaypointMissionExecuteState.RECOVERING -> WaylinePhase.RECOVERING
        WaypointMissionExecuteState.FINISHED -> WaylinePhase.FINISHED
        WaypointMissionExecuteState.UNKNOWN -> WaylinePhase.ERROR
    }
}
