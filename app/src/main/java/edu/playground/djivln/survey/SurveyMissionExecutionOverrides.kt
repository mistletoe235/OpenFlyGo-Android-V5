package edu.playground.djivln.survey

import kotlin.math.abs

object SurveyMissionExecutionOverrides {
    fun activeRecaptureRouteSpeed(
        mission: SurveyMission,
        speedMetersPerSecond: Double,
        obliqueSpeedMetersPerSecond: Double = speedMetersPerSecond,
    ): SurveyMission {
        require(mission.activeMapping != null) { "only active recapture missions accept execution-only overrides" }
        require(speedMetersPerSecond in 0.5..10.0) { "speed must be within 0.5-10.0 m/s" }
        require(obliqueSpeedMetersPerSecond in 0.5..10.0) {
            "oblique speed must be within 0.5-10.0 m/s"
        }
        if (abs(mission.constraints.speedMetersPerSecond - speedMetersPerSecond) < 1.0e-6 &&
            abs(mission.constraints.obliqueSpeedMetersPerSecond - obliqueSpeedMetersPerSecond) < 1.0e-6
        ) return mission
        val constraints = mission.constraints.copy(
            speedMetersPerSecond = speedMetersPerSecond,
            obliqueSpeedMetersPerSecond = obliqueSpeedMetersPerSecond,
        )
        val estimatedSeconds = if (mission.waypoints.size >= 2) {
            SurveyPlanner.estimateRouteSeconds(mission.waypoints, constraints)
        } else {
            mission.estimatedPathMeters /
                constraints.speedForCaptureView(mission.waypoints.firstOrNull()?.captureView ?: SurveyCaptureView.NADIR)
        }
        return mission.copy(
            constraints = constraints,
            estimatedFlightSeconds = estimatedSeconds,
        )
    }
}
