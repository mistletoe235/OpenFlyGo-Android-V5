package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.domain.wayline.WaylineState

object SurveyWaylineExport {
    fun checkpoint(mission: SurveyMission, state: WaylineState, nowEpochMillis: Long): SurveyExecutionCheckpoint? {
        val waypointIndex = state.waypointIndex ?: return null
        if (waypointIndex !in mission.waypoints.indices) return null
        val executionState = state.phase.toExecutionState()
        if (state.phase == WaylinePhase.PREPARING && state.breakpoint == null) return null
        if (executionState !in setOf(
                SurveyExecutionState.ARMING,
                SurveyExecutionState.RUNNING,
                SurveyExecutionState.PAUSED,
            )
        ) return null
        return SurveyExecutionCheckpoint(
            missionId = mission.id,
            waypointIndex = waypointIndex,
            state = executionState,
            updatedAtEpochMillis = nowEpochMillis,
            executionLegIndex = waypointIndex,
            phase = SurveyExecutionPhase.SURVEY,
            backend = SurveyExecutionBackend.DJI_KMZ,
            djiBreakpoint = state.breakpoint,
        )
    }

    private fun WaylinePhase.toExecutionState(): SurveyExecutionState = when (this) {
        WaylinePhase.PREPARING, WaylinePhase.RECOVERING -> SurveyExecutionState.ARMING
        WaylinePhase.EXECUTING -> SurveyExecutionState.RUNNING
        WaylinePhase.PAUSED -> SurveyExecutionState.PAUSED
        WaylinePhase.FINISHED -> SurveyExecutionState.COMPLETED
        WaylinePhase.ERROR, WaylinePhase.UNSUPPORTED, WaylinePhase.DISCONNECTED -> SurveyExecutionState.ABORTED
        WaylinePhase.IDLE, WaylinePhase.UPLOADING, WaylinePhase.READY -> SurveyExecutionState.IDLE
    }
}
