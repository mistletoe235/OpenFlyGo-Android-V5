package edu.playground.djivln.survey

object SurveyNadirGimbalPolicy {
    fun isMechanicalLimit(
        targetPitchDegrees: Double,
        settled: Boolean,
        pitchAtStop: Boolean,
    ): Boolean = !settled && targetPitchDegrees <= -89.0 && pitchAtStop

    fun shouldDeferCaptureStart(
        captureAction: CaptureAction,
        mechanicalLimit: Boolean,
    ): Boolean = captureAction == CaptureAction.START_DISTANCE_INTERVAL && mechanicalLimit

    fun canCapture(settled: Boolean): Boolean = settled

    fun shouldSkipEndFrame(
        captureAction: CaptureAction,
        settled: Boolean,
    ): Boolean = captureAction == CaptureAction.STOP_DISTANCE_INTERVAL && !settled
}
