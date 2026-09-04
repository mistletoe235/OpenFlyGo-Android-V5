package edu.playground.djivln.survey

import org.json.JSONObject

data class SurveyUeTelemetry(
    val timestampEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeAglMeters: Double,
    val headingDegrees: Double,
    val gimbalPitchDegrees: Double,
    val simulatorActive: Boolean,
    val simulatorFlying: Boolean,
    val executionState: SurveyExecutionState,
    val waypointIndex: Int,
)

data class SurveyUeTarget(
    val timestampEpochMillis: Long,
    val missionId: String,
    val executionState: SurveyExecutionState,
    val phase: SurveyExecutionPhase,
    val executionLegIndex: Int,
    val waypointIndex: Int,
    val waypoint: SurveyWaypoint,
)

data class SurveyUeCapture(
    val timestampEpochMillis: Long,
    val missionId: String,
    val reason: String,
    val frameId: Long,
    val poseSequence: Long,
    val frameFormat: String,
    val width: Int,
    val height: Int,
    val capturePeerMonotonicNanos: Long,
    val savedPath: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeAglMeters: Double,
    val headingDegrees: Double,
    val gimbalPitchDegrees: Double,
    val executionLegIndex: Int,
    val waypointIndex: Int,
)

object SurveyUeBridgeContract {
    const val SCHEMA = "openfly.survey.ue.v1"

    fun encodeMission(mission: SurveyMission): String = envelope("mission")
        .put("mission", JSONObject(SurveyMissionJson.encode(mission)))
        .toString()

    fun encodeTelemetry(value: SurveyUeTelemetry): String = envelope("telemetry")
        .put("timestamp_epoch_ms", value.timestampEpochMillis)
        .put("pose", JSONObject()
            .put("latitude_wgs84_deg", value.latitude)
            .put("longitude_wgs84_deg", value.longitude)
            .put("altitude_agl_m", value.altitudeAglMeters)
            .put("heading_cw_from_north_deg", value.headingDegrees)
            .put("gimbal_pitch_deg", value.gimbalPitchDegrees))
        .put("dji_simulator", JSONObject()
            .put("active", value.simulatorActive)
            .put("flying", value.simulatorFlying))
        .put("execution", JSONObject()
            .put("state", value.executionState.name)
            .put("waypoint_index", value.waypointIndex))
        .toString()

    fun encodeTarget(value: SurveyUeTarget): String = envelope("target")
        .put("timestamp_epoch_ms", value.timestampEpochMillis)
        .put("mission_id", value.missionId)
        .put("execution", JSONObject()
            .put("state", value.executionState.name)
            .put("phase", value.phase.name)
            .put("execution_leg_index", value.executionLegIndex)
            .put("waypoint_index", value.waypointIndex))
        .put("target", encodeWaypoint(value.waypoint))
        .toString()

    fun encodeCapture(value: SurveyUeCapture): String = envelope("capture")
        .put("timestamp_epoch_ms", value.timestampEpochMillis)
        .put("mission_id", value.missionId)
        .put("reason", value.reason)
        .put("frame_id", value.frameId)
        .put("pose_sequence", value.poseSequence)
        .put("frame", JSONObject()
            .put("format", value.frameFormat)
            .put("width", value.width)
            .put("height", value.height)
            .put("capture_peer_monotonic_ns", value.capturePeerMonotonicNanos))
        .put("saved_path", value.savedPath)
        .put("pose", JSONObject()
            .put("latitude_wgs84_deg", value.latitude)
            .put("longitude_wgs84_deg", value.longitude)
            .put("altitude_agl_m", value.altitudeAglMeters)
            .put("heading_cw_from_north_deg", value.headingDegrees)
            .put("gimbal_pitch_deg", value.gimbalPitchDegrees))
        .put("execution", JSONObject()
            .put("execution_leg_index", value.executionLegIndex)
            .put("waypoint_index", value.waypointIndex))
        .toString()

    private fun encodeWaypoint(value: SurveyWaypoint) = JSONObject()
        .put("latitude_wgs84_deg", value.point.latitude)
        .put("longitude_wgs84_deg", value.point.longitude)
        .put("altitude_agl_m", value.point.altitudeMeters)
        .put("heading_cw_from_north_deg", value.headingDegrees)
        .put("gimbal_pitch_deg", value.gimbalPitchDegrees)
        .put("kind", value.kind.name)
        .put("capture_action", value.captureAction.name)
        .put("capture_view", value.captureView.name)
        .put("pass_index", value.passIndex)

    private fun envelope(type: String) = JSONObject()
        .put("schema", SCHEMA)
        .put("type", type)
        .put("coordinate_contract", JSONObject()
            .put("geodetic", "WGS84")
            .put("local_world", "ENU")
            .put("vehicle_body", "FRU")
            .put("heading", "clockwise_from_true_north_degrees")
            .put("gimbal_pitch", "negative_is_down_degrees")
            .put("linear_units", "meters"))
}
