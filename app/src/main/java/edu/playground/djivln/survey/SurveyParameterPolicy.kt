package edu.playground.djivln.survey

import edu.playground.djivln.R

object SurveyParameterPolicy {
    fun createConstraints(
        altitudeMetersAgl: Double,
        routeHeadingDegrees: Double,
        forwardOverlapPercent: Double,
        sideOverlapPercent: Double,
        speedMetersPerSecond: Double,
        gimbalPitchDegrees: Double,
        boundaryMarginMeters: Double,
        obliqueFiveDirection: Boolean,
        targetSurfaceToTakeoffMeters: Double = 0.0,
        safeTakeoffAltitudeMeters: Double = 30.0,
        takeoffSpeedMetersPerSecond: Double = 3.0,
        descentSpeedMetersPerSecond: Double = 2.0,
        obliqueForwardOverlapPercent: Double = 70.0,
        obliqueSideOverlapPercent: Double = 60.0,
        altitudeMode: SurveyAltitudeMode = SurveyAltitudeMode.ABOVE_TARGET_SURFACE,
        startPointMode: SurveyStartPointMode = SurveyStartPointMode.AUTO_NEAREST,
        completionAction: SurveyCompletionAction = SurveyCompletionAction.RETURN_TO_HOME,
        captureTriggerMode: SurveyCaptureTriggerMode = SurveyCaptureTriggerMode.DISTANCE,
        timedCaptureIntervalSeconds: Double = 1.0,
        takeoffMode: SurveyTakeoffMode = SurveyTakeoffMode.MANUAL,
        enabledCaptureViews: Set<SurveyCaptureView> = STANDARD_SURVEY_CAPTURE_VIEWS,
        obliqueHeadingMode: SurveyObliqueHeadingMode = SurveyObliqueHeadingMode.TRACK_ROUTE,
        obliqueSpeedMetersPerSecond: Double = speedMetersPerSecond,
        message: (Int) -> String = ::defaultMessage,
    ): SurveyConstraints {
        require(altitudeMetersAgl in 10.0..120.0) { message(R.string.survey_altitude_range_error) }
        require(routeHeadingDegrees.isFinite()) { message(R.string.survey_heading_number_error) }
        require(forwardOverlapPercent in 50.0..90.0) { message(R.string.survey_forward_overlap_range_error) }
        require(sideOverlapPercent in 40.0..90.0) { message(R.string.survey_side_overlap_range_error) }
        require(speedMetersPerSecond in 0.5..10.0) { message(R.string.survey_speed_range_error) }
        require(obliqueSpeedMetersPerSecond in 0.5..10.0) { message(R.string.survey_oblique_speed_range_error) }
        if (obliqueFiveDirection) {
            require(gimbalPitchDegrees in -80.0..-30.0) { message(R.string.survey_five_direction_pitch_range_error) }
        } else {
            require(gimbalPitchDegrees in -90.0..-30.0) { message(R.string.survey_gimbal_pitch_range_error) }
        }
        require(boundaryMarginMeters in 0.0..30.0) { message(R.string.survey_boundary_margin_range_error) }
        require(targetSurfaceToTakeoffMeters in -500.0..500.0) { message(R.string.survey_target_surface_range_error) }
        require(safeTakeoffAltitudeMeters in 5.0..120.0) { message(R.string.survey_safe_takeoff_altitude_range_error) }
        require(takeoffSpeedMetersPerSecond in 0.5..6.0) { message(R.string.survey_ascent_speed_range_error) }
        require(descentSpeedMetersPerSecond in 0.5..6.0) { message(R.string.survey_descent_speed_range_error) }
        require(obliqueForwardOverlapPercent in 50.0..90.0) { message(R.string.survey_oblique_forward_overlap_range_error) }
        require(obliqueSideOverlapPercent in 40.0..90.0) { message(R.string.survey_oblique_side_overlap_range_error) }
        require(timedCaptureIntervalSeconds in 1.0..60.0) { message(R.string.survey_capture_interval_range_error) }
        val effectiveFlightAltitudeMeters = if (altitudeMode == SurveyAltitudeMode.ABOVE_TARGET_SURFACE) {
            altitudeMetersAgl + targetSurfaceToTakeoffMeters
        } else {
            altitudeMetersAgl
        }
        require(effectiveFlightAltitudeMeters in 5.0..120.0) {
            message(R.string.survey_waypoint_relative_altitude_range_error)
        }
        val normalizedHeading = ((routeHeadingDegrees % 360.0) + 360.0) % 360.0
        return SurveyConstraints(
            altitudeMetersAgl = altitudeMetersAgl,
            forwardOverlap = forwardOverlapPercent / 100.0,
            sideOverlap = sideOverlapPercent / 100.0,
            speedMetersPerSecond = speedMetersPerSecond,
            obliqueSpeedMetersPerSecond = obliqueSpeedMetersPerSecond,
            gimbalPitchDegrees = -90.0,
            routeHeadingDegrees = normalizedHeading,
            crosshatch = false,
            collectionMode = if (obliqueFiveDirection) {
                SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION
            } else {
                SurveyCollectionMode.ORTHO
            },
            obliqueGimbalPitchDegrees = gimbalPitchDegrees,
            boundaryMarginMeters = boundaryMarginMeters,
            altitudeMode = altitudeMode,
            targetSurfaceToTakeoffMeters = targetSurfaceToTakeoffMeters,
            safeTakeoffAltitudeMeters = safeTakeoffAltitudeMeters,
            takeoffSpeedMetersPerSecond = takeoffSpeedMetersPerSecond,
            descentSpeedMetersPerSecond = descentSpeedMetersPerSecond,
            takeoffMode = takeoffMode,
            obliqueForwardOverlap = obliqueForwardOverlapPercent / 100.0,
            obliqueSideOverlap = obliqueSideOverlapPercent / 100.0,
            startPointMode = startPointMode,
            completionAction = completionAction,
            captureTriggerMode = captureTriggerMode,
            timedCaptureIntervalSeconds = timedCaptureIntervalSeconds,
            enabledCaptureViews = enabledCaptureViews,
            obliqueHeadingMode = obliqueHeadingMode,
        )
    }

    private fun defaultMessage(resourceId: Int): String = when (resourceId) {
        R.string.survey_altitude_range_error -> "Altitude must be 10–120 m"
        R.string.survey_heading_number_error -> "Heading must be a valid number"
        R.string.survey_forward_overlap_range_error -> "Forward overlap must be 50–90%"
        R.string.survey_side_overlap_range_error -> "Side overlap must be 40–90%"
        R.string.survey_speed_range_error -> "Planning speed must be 0.5–10.0 m/s"
        R.string.survey_oblique_speed_range_error -> "Oblique speed must be 0.5–10.0 m/s"
        R.string.survey_five_direction_pitch_range_error -> "Five-direction oblique pitch must be -80° to -30°"
        R.string.survey_gimbal_pitch_range_error -> "Gimbal pitch must be -90° to -30°"
        R.string.survey_boundary_margin_range_error -> "Boundary margin must be 0–30 m"
        R.string.survey_target_surface_range_error -> "Target surface offset must be -500–500 m"
        R.string.survey_safe_takeoff_altitude_range_error -> "Safe takeoff altitude must be 5–120 m"
        R.string.survey_ascent_speed_range_error -> "Ascent speed must be 0.5–6.0 m/s"
        R.string.survey_descent_speed_range_error -> "Descent speed must be 0.5–6.0 m/s"
        R.string.survey_oblique_forward_overlap_range_error -> "Oblique forward overlap must be 50–90%"
        R.string.survey_oblique_side_overlap_range_error -> "Oblique side overlap must be 40–90%"
        R.string.survey_capture_interval_range_error -> "Timed capture interval must be 1–60 s"
        R.string.survey_waypoint_relative_altitude_range_error -> "Waypoint altitude relative to takeoff must be 5–120 m"
        else -> "Invalid survey parameter"
    }
}
