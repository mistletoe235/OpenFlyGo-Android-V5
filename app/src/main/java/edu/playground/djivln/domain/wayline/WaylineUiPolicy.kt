package edu.playground.djivln.domain.wayline

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

data class WaylineUiActions(
    val canUpload: Boolean,
    val canExecute: Boolean,
    val canPause: Boolean,
    val canResume: Boolean,
    val canStop: Boolean
)

object WaylineUiPolicy {
    fun actions(aircraftConnected: Boolean, kmzReady: Boolean, state: WaylineState): WaylineUiActions {
        val backendAvailable = unavailableReason(aircraftConnected, state) == null
        val connectedAndReady = backendAvailable && kmzReady
        return WaylineUiActions(
            canUpload = connectedAndReady && state.phase !in ACTIVE_PHASES,
            canExecute = connectedAndReady && state.phase == WaylinePhase.READY,
            canPause = backendAvailable && state.phase in setOf(WaylinePhase.PREPARING, WaylinePhase.EXECUTING),
            canResume = backendAvailable && state.phase == WaylinePhase.PAUSED,
            canStop = backendAvailable && state.phase in ACTIVE_PHASES,
        )
    }

    fun unavailableReason(aircraftConnected: Boolean, state: WaylineState): UiText? = when {
        !aircraftConnected -> UiText.resource(R.string.status_aircraft_disconnected)
        state.phase == WaylinePhase.DISCONNECTED -> UiText.resource(R.string.wayline_service_disconnected)
        state.phase == WaylinePhase.UNSUPPORTED -> UiText.resource(R.string.wayline_unsupported_aircraft)
        else -> null
    }

    private val ACTIVE_PHASES = setOf(
        WaylinePhase.UPLOADING,
        WaylinePhase.PREPARING,
        WaylinePhase.EXECUTING,
        WaylinePhase.PAUSED,
        WaylinePhase.RECOVERING,
    )
}
