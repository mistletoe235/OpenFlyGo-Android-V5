package edu.playground.djivln.ui

import edu.playground.djivln.survey.ActiveMappingPassMetadata
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.SurveyCaptureView
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.surveyPasses

/** Route-group palette kept identical to the V4 survey map. */
internal object SurveyRouteStyle {
    fun color(captureView: SurveyCaptureView): Int = when (captureView) {
        SurveyCaptureView.NADIR -> 0xFFFFB547.toInt()
        SurveyCaptureView.FORWARD_OBLIQUE -> 0xFFFF6B6B.toInt()
        SurveyCaptureView.BACKWARD_OBLIQUE -> 0xFFB77BFF.toInt()
        SurveyCaptureView.LEFT_OBLIQUE -> 0xFF55D69E.toInt()
        SurveyCaptureView.RIGHT_OBLIQUE -> 0xFF55BDEB.toInt()
        SurveyCaptureView.LOCAL_OBLIQUE -> 0xFFFF8A3D.toInt()
    }

    fun width(captureView: SurveyCaptureView, terrainColored: Boolean): Float = when {
        terrainColored && captureView == SurveyCaptureView.NADIR -> 6.5f
        terrainColored -> 5f
        captureView == SurveyCaptureView.NADIR -> 4.5f
        else -> 3.5f
    }

    fun isPureBridge(metadata: ActiveMappingPassMetadata?): Boolean =
        metadata?.role == "RECONSTRUCTION_BRIDGE" && metadata.captureRole == "BRIDGE"

    fun regionId(mission: SurveyMission, waypointIndex: Int): String? {
        val passIndex = mission.surveyPasses()
            .firstOrNull { waypointIndex in it.firstWaypointIndex..it.lastWaypointIndex }
            ?.start
            ?.passIndex
            ?: return null
        return mission.activeMapping?.passes?.firstOrNull { it.passIndex == passIndex }?.regionId
    }

    fun activeRoutePoints(mission: SurveyMission, waypointIndex: Int): List<GeoPoint> {
        return mission.surveyPasses()
            .firstOrNull { waypointIndex in it.firstWaypointIndex..it.lastWaypointIndex }
            ?.waypoints
            ?.map { it.point }
            .orEmpty()
    }
}
