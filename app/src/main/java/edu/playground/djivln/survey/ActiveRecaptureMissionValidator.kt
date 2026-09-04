package edu.playground.djivln.survey

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

data class ActiveRecaptureValidationReport(
    val captureCount: Int,
    val pointCaptureCount: Int,
    val continuousPassCount: Int,
    val minimumAltitudeMeters: Double,
    val maximumAltitudeMeters: Double,
    val maximumAdjacentDistanceMeters: Double,
    val maximumYawStepDegrees: Double,
    val maximumGimbalPitchStepDegrees: Double,
)

object ActiveRecaptureMissionValidator {
    const val MIN_ALTITUDE_METERS = 5.0
    const val MAX_ALTITUDE_METERS = 120.0
    const val MAX_ADJACENT_DISTANCE_METERS = 25.0
    const val MAX_YAW_STEP_DEGREES = 45.0
    const val MAX_GIMBAL_PITCH_STEP_DEGREES = 15.0

    @JvmStatic
    fun validate(mission: SurveyMission): ActiveRecaptureValidationReport {
        val metadata = requireNotNull(mission.activeMapping) {
            "active recapture mission is missing active_mapping metadata"
        }
        val passes = mission.surveyPasses()
        val schedule = SurveyCaptureSchedule.build(mission)
        require(schedule.size == metadata.sourceCaptureCount) {
            "active recapture capture count does not match source"
        }
        require(metadata.surveyCaptureCount + metadata.bridgeCaptureCount == metadata.sourceCaptureCount) {
            "active recapture metadata capture counts are inconsistent"
        }
        require(metadata.passes.size == passes.size) {
            "active recapture pass metadata count does not match mission"
        }
        require(metadata.passes.map { it.passIndex } == passes.map { it.start.passIndex }) {
            "active recapture pass metadata is not aligned with mission passes"
        }
        val passIndices = passes.map { it.start.passIndex }.toSet()
        require(metadata.regions.flatMap { it.passIndices }.all { it in passIndices }) {
            "active recapture region references an unknown pass"
        }
        val altitudes = mission.waypoints.map { it.point.altitudeMeters }
        require(altitudes.all { it in MIN_ALTITUDE_METERS..MAX_ALTITUDE_METERS }) {
            "active recapture waypoint altitude is outside 5-120 m"
        }
        val pairs = mission.waypoints.zipWithNext()
        val maximumDistance = pairs.maxOfOrNull { (a, b) -> distanceMeters(a.point, b.point) } ?: 0.0
        val maximumYaw = pairs.maxOfOrNull { (a, b) -> angleDifference(a.headingDegrees, b.headingDegrees) } ?: 0.0
        val maximumPitch = pairs.maxOfOrNull { (a, b) ->
            abs(a.gimbalPitchDegrees - b.gimbalPitchDegrees)
        } ?: 0.0
        require(maximumDistance <= MAX_ADJACENT_DISTANCE_METERS + 1.0e-6) {
            "active recapture adjacent waypoint distance exceeds 25 m"
        }
        require(maximumYaw <= MAX_YAW_STEP_DEGREES + 1.0e-6) {
            "active recapture yaw step exceeds 45 degrees"
        }
        require(maximumPitch <= MAX_GIMBAL_PITCH_STEP_DEGREES + 1.0e-6) {
            "active recapture gimbal pitch step exceeds 15 degrees"
        }
        val highRisePassIndices = metadata.passes.filter { it.regionId == "R8_R9" }
            .map { it.passIndex }
            .toSet()
        if (highRisePassIndices.isNotEmpty()) {
            val views = passes.filter { it.start.passIndex in highRisePassIndices }
                .map { it.start.captureView }
                .toSet()
            require(views.containsAll(STANDARD_SURVEY_CAPTURE_VIEWS)) {
                "R8_R9 must include nadir and four oblique capture directions"
            }
        }
        val pointCaptures = passes.count { it.isPointCapture }
        require(passes.filter { it.isPointCapture }.all {
            it.start.captureView == SurveyCaptureView.LOCAL_OBLIQUE
        }) { "precise active recapture points must use LOCAL_OBLIQUE" }
        return ActiveRecaptureValidationReport(
            captureCount = schedule.size,
            pointCaptureCount = pointCaptures,
            continuousPassCount = passes.size - pointCaptures,
            minimumAltitudeMeters = altitudes.min(),
            maximumAltitudeMeters = altitudes.max(),
            maximumAdjacentDistanceMeters = maximumDistance,
            maximumYawStepDegrees = maximumYaw,
            maximumGimbalPitchStepDegrees = maximumPitch,
        )
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 *
            cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return hypot(north, east)
    }

    private fun angleDifference(a: Double, b: Double): Double =
        abs((b - a + 540.0) % 360.0 - 180.0)
}
