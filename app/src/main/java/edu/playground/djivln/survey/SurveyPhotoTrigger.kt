package edu.playground.djivln.survey

data class SurveyPhotoTrigger(
    val missionId: String,
    val backend: SurveyExecutionBackend,
    val passIndex: Int?,
    val waypointIndex: Int?,
    val captureView: String?,
    val reason: String,
    val triggeredAtNanos: Long,
    val triggeredAtEpochMillis: Long,
)
