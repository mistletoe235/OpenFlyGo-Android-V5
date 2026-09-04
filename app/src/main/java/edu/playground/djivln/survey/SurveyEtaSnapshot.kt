package edu.playground.djivln.survey

enum class SurveyEtaPhase { PLANNED, REMAINING, PAUSED, COMPLETED }

/** One estimate is published to both the planner and the main-screen map badge. */
data class SurveyEtaSnapshot(
    val missionId: String,
    val estimate: SurveyRemainingEstimate,
    val phase: SurveyEtaPhase,
)
