package edu.playground.djivln.survey

import org.json.JSONArray
import org.json.JSONObject

object SurveyCaptureScheduleJson {
    fun encode(mission: SurveyMission, events: List<SurveyCaptureEvent>): String = JSONObject()
        .put("schema_version", 1)
        .put("mission", JSONObject(SurveyMissionJson.encode(mission)))
        .put("capture_events", JSONArray().apply {
            events.forEach { event ->
                put(JSONObject()
                    .put("capture_index", event.captureIndex)
                    .put("latitude", event.point.latitude)
                    .put("longitude", event.point.longitude)
                    .put("altitude_m", event.point.altitudeMeters)
                    .put("heading_deg", event.headingDegrees)
                    .put("gimbal_pitch_deg", event.gimbalPitchDegrees)
                    .put("pass_index", event.passIndex)
                    .put("capture_view", event.captureView.name)
                    .put("distance_along_pass_m", event.distanceAlongPassMeters)
                    .put("pass_length_m", event.passLengthMeters)
                    .put("estimated_mission_distance_m", event.estimatedMissionDistanceMeters))
            }
        })
        .toString(2)
}
