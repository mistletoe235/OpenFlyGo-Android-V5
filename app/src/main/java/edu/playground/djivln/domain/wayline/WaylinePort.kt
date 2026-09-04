package edu.playground.djivln.domain.wayline

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

enum class WaylinePhase {
    IDLE,
    UPLOADING,
    READY,
    PREPARING,
    EXECUTING,
    PAUSED,
    RECOVERING,
    FINISHED,
    ERROR,
    UNSUPPORTED,
    DISCONNECTED
}

data class WaylineState(
    val phase: WaylinePhase = WaylinePhase.IDLE,
    val missionFileName: String? = null,
    val waylineId: Int? = null,
    val waypointIndex: Int? = null,
    val uploadProgress: Double = 0.0,
    val message: UiText = UiText.resource(R.string.wayline_not_loaded),
    val error: String? = null,
    val breakpoint: WaylineBreakpoint? = null,
    val actionEvent: WaylineActionEvent? = null,
)

data class WaylineActionEvent(
    val actionGroupId: Int? = null,
    val actionId: Int,
    val started: Boolean,
    val error: String? = null,
)

data class WaylineBreakpoint(
    val waylineId: Int,
    val waypointId: Int,
    val segmentProgress: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val recoverActionType: String? = null,
) {
    init {
        require(waylineId >= 0)
        require(waypointId >= 0)
        require(segmentProgress in 0.0..1.0)
    }
}

fun interface WaylineStateListener {
    fun onStateChanged(state: WaylineState)
}

fun interface WaylineCompletion {
    fun complete(result: Result<Unit>)
}

fun interface WaylineBreakpointCompletion {
    fun complete(result: Result<WaylineBreakpoint?>)
}

interface WaylinePort {
    fun start(listener: WaylineStateListener)
    fun stop()
    fun state(): WaylineState
    /**
     * Reconciles a missing SDK execution callback with an independently confirmed aircraft
     * waypoint-flight mode. Implementations must ignore this outside an active start/recovery.
     */
    fun confirmExecutionFromTelemetry(): Boolean = false
    fun availableWaylineIds(missionFileName: String): List<Int> = emptyList()
    fun upload(kmzPath: String, completion: WaylineCompletion)
    fun execute(missionFileName: String, waylineIds: List<Int> = emptyList(), completion: WaylineCompletion)
    fun executeFromBreakpoint(
        missionFileName: String,
        breakpoint: WaylineBreakpoint,
        completion: WaylineCompletion,
    )
    fun pause(completion: WaylineCompletion)
    fun resume(completion: WaylineCompletion)
    fun resume(breakpoint: WaylineBreakpoint, completion: WaylineCompletion)
    fun queryBreakpoint(missionFileName: String, completion: WaylineBreakpointCompletion)
    fun stopMission(missionFileName: String, completion: WaylineCompletion)
}
