package edu.playground.djivln.survey

enum class RecaptureFlightMode {
    STOP_AND_CAPTURE,
    CONTINUOUS_EXPERIMENTAL,
}

object RecaptureFlightModePolicy {
    fun canExecute(mission: SurveyMission, djiBackend: Boolean): Boolean =
        mission.recaptureFlightMode == RecaptureFlightMode.STOP_AND_CAPTURE ||
            (mission.activeMapping != null && djiBackend)
}
