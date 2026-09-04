package edu.playground.djivln.survey

/** Display-only action budgets shared by planned and live ETA estimators. */
object SurveyEtaPolicy {
    const val CAPTURE_ON_REACH_SECONDS = 2.0

    fun captureDelaySeconds(action: CaptureAction): Double =
        if (action == CaptureAction.CAPTURE_ON_REACH) CAPTURE_ON_REACH_SECONDS else 0.0
}
