package edu.playground.djivln.survey

data class SurveyCameraExecutionAssessment(
    val gate: SurveyExecutionGateResult,
    val geometryWarning: Boolean,
)

object SurveyCameraExecutionPolicy {
    fun evaluate(
        flightGate: SurveyExecutionGateResult,
        cameraConnected: Boolean,
        geometryConfirmed: Boolean,
    ): SurveyCameraExecutionAssessment {
        val gate = if (cameraConnected) flightGate else flightGate.copy(
            allowed = false,
            blocks = flightGate.blocks + SurveyExecutionBlock.CAMERA_UNAVAILABLE,
        )
        return SurveyCameraExecutionAssessment(gate, cameraConnected && !geometryConfirmed)
    }
}
