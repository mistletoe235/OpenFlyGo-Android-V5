package edu.playground.djivln.survey

import org.json.JSONArray
import org.json.JSONObject

object SurveyMissionJson {
    const val SCHEMA_VERSION = 13

    fun encode(mission: SurveyMission): String {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("id", mission.id)
        root.put("name", mission.name)
        root.put("created_at_epoch_ms", mission.createdAtEpochMillis)
        root.put("coordinate_frame", mission.coordinateFrame)
        root.put("camera_profile", encodeCamera(mission.cameraProfile))
        root.put("constraints", encodeConstraints(mission.constraints))
        root.put("roi", JSONArray().apply { mission.roi.forEach { put(encodePoint(it)) } })
        root.put("waypoints", JSONArray().apply { mission.waypoints.forEach { put(encodeWaypoint(it)) } })
        root.put("estimated_path_m", mission.estimatedPathMeters)
        root.put("estimated_photo_count", mission.estimatedPhotoCount)
        root.put("estimated_flight_s", mission.estimatedFlightSeconds)
        root.put("terrain_plan", mission.terrainPlan?.let(::encodeTerrainPlan) ?: JSONObject.NULL)
        root.put("active_mapping", mission.activeMapping?.let(::encodeActiveMapping) ?: JSONObject.NULL)
        return root.toString(2)
    }

    fun decode(raw: String): SurveyMission {
        val root = JSONObject(raw)
        val schemaVersion = root.getInt("schema_version")
        require(schemaVersion in 1..SCHEMA_VERSION) { "unsupported mission schema" }
        val coordinateFrame = root.getString("coordinate_frame")
        require(coordinateFrame == "WGS84") { "only WGS84 missions are supported" }
        return SurveyMission(
            id = root.getString("id"),
            name = root.getString("name"),
            createdAtEpochMillis = root.getLong("created_at_epoch_ms"),
            coordinateFrame = coordinateFrame,
            cameraProfile = decodeCamera(root.getJSONObject("camera_profile")),
            constraints = decodeConstraints(root.getJSONObject("constraints"), schemaVersion),
            roi = decodeArray(root.getJSONArray("roi"), ::decodePoint),
            waypoints = decodeArray(root.getJSONArray("waypoints")) { decodeWaypoint(it, schemaVersion) },
            estimatedPathMeters = root.getDouble("estimated_path_m"),
            estimatedPhotoCount = root.getInt("estimated_photo_count"),
            estimatedFlightSeconds = root.getDouble("estimated_flight_s"),
            terrainPlan = if (schemaVersion >= 7 && !root.isNull("terrain_plan")) {
                decodeTerrainPlan(root.getJSONObject("terrain_plan"), schemaVersion)
            } else null,
            activeMapping = if (schemaVersion >= 11 && !root.isNull("active_mapping")) {
                decodeActiveMapping(root.getJSONObject("active_mapping"))
            } else null,
        )
    }

    private fun encodeActiveMapping(value: ActiveMappingMetadata) = JSONObject()
        .put("schema_version", value.schemaVersion)
        .put("selection_method", value.selectionMethod)
        .put("ground_truth_used", value.groundTruthUsed)
        .put("gs_used_for_selection", value.gsUsedForSelection)
        .put("ordinary_gps_used", value.ordinaryGpsUsed)
        .put("source_capture_count", value.sourceCaptureCount)
        .put("survey_capture_count", value.surveyCaptureCount)
        .put("bridge_capture_count", value.bridgeCaptureCount)
        .put("source_estimated_route_distance_m", value.sourceEstimatedRouteDistanceMeters)
        .put("regions", JSONArray().apply { value.regions.forEach { put(encodeActiveRegion(it)) } })
        .put("passes", JSONArray().apply { value.passes.forEach { put(encodeActivePass(it)) } })

    private fun decodeActiveMapping(value: JSONObject) = ActiveMappingMetadata(
        schemaVersion = value.optInt("schema_version", 1),
        selectionMethod = value.getString("selection_method"),
        groundTruthUsed = value.optBoolean("ground_truth_used", false),
        gsUsedForSelection = value.optBoolean("gs_used_for_selection", false),
        ordinaryGpsUsed = value.optBoolean("ordinary_gps_used", true),
        sourceCaptureCount = value.getInt("source_capture_count"),
        surveyCaptureCount = value.getInt("survey_capture_count"),
        bridgeCaptureCount = value.getInt("bridge_capture_count"),
        sourceEstimatedRouteDistanceMeters = value.getDouble("source_estimated_route_distance_m"),
        regions = decodeArray(value.optJSONArray("regions") ?: JSONArray(), ::decodeActiveRegion),
        passes = decodeArray(value.optJSONArray("passes") ?: JSONArray(), ::decodeActivePass),
    )

    private fun encodeActiveRegion(value: ActiveMappingRegionMetadata) = JSONObject()
        .put("region_id", value.regionId)
        .put("priority", value.priority)
        .put("kind", value.kind)
        .put("risk_score", value.riskScore)
        .put("reasons", JSONArray(value.reasons))
        .put("target_wgs84", value.targetWgs84?.let(::encodeActiveTarget) ?: JSONObject.NULL)
        .put("pass_indices", JSONArray(value.passIndices))
        .put("suggested_survey_photos", value.suggestedSurveyPhotos)

    private fun decodeActiveRegion(value: JSONObject) = ActiveMappingRegionMetadata(
        regionId = value.getString("region_id"),
        priority = value.getInt("priority"),
        kind = value.getString("kind"),
        riskScore = value.getDouble("risk_score"),
        reasons = decodeStringArray(value.optJSONArray("reasons") ?: JSONArray()),
        targetWgs84 = if (value.isNull("target_wgs84")) null else {
            decodeActiveTarget(value.getJSONObject("target_wgs84"))
        },
        passIndices = decodeIntArray(value.optJSONArray("pass_indices") ?: JSONArray()),
        suggestedSurveyPhotos = value.optInt("suggested_survey_photos", 0),
    )

    private fun encodeActiveTarget(value: ActiveMappingTarget) = JSONObject()
        .put("latitude", value.latitude)
        .put("longitude", value.longitude)
        .put("absolute_altitude_m", value.absoluteAltitudeMeters)

    private fun decodeActiveTarget(value: JSONObject) = ActiveMappingTarget(
        latitude = value.getDouble("latitude"),
        longitude = value.getDouble("longitude"),
        absoluteAltitudeMeters = value.getDouble("absolute_altitude_m"),
    )

    private fun encodeActivePass(value: ActiveMappingPassMetadata) = JSONObject()
        .put("pass_index", value.passIndex)
        .put("region_id", value.regionId)
        .put("role", value.role)
        .put("capture_role", value.captureRole)
        .put("source", value.source)
        .put("required_for_reconstruction_bridge", value.requiredForReconstructionBridge)

    private fun decodeActivePass(value: JSONObject) = ActiveMappingPassMetadata(
        passIndex = value.getInt("pass_index"),
        regionId = value.getString("region_id"),
        role = value.getString("role"),
        captureRole = value.getString("capture_role"),
        source = value.getString("source"),
        requiredForReconstructionBridge = value.optBoolean(
            "required_for_reconstruction_bridge",
            false,
        ),
    )

    private fun encodeTerrainPlan(value: SurveyTerrainPlan) = JSONObject()
        .put("source_name", value.sourceName)
        .put("source_sha256", value.sourceSha256)
        .put("source_kind", value.sourceKind.name)
        .put("bare_earth_base_sha256", value.bareEarthBaseSha256 ?: JSONObject.NULL)
        .put("epsg", value.epsg)
        .put("target_agl_m", value.targetAglMeters)
        .put("takeoff_terrain_elevation_m", value.takeoffTerrainElevationMeters)
        .put("sample_spacing_m", value.sampleSpacingMeters)
        .put("minimum_terrain_elevation_m", value.minimumTerrainElevationMeters)
        .put("maximum_terrain_elevation_m", value.maximumTerrainElevationMeters)
        .put("minimum_waypoint_altitude_m", value.minimumWaypointAltitudeMeters)
        .put("maximum_waypoint_altitude_m", value.maximumWaypointAltitudeMeters)
        .put("real_flight_verified", value.realFlightVerified)
        .put("takeoff_reference", value.takeoffReference?.let { reference ->
            JSONObject()
                .put("point", encodePoint(reference.point))
                .put("source", reference.source.name)
                .put("captured_at_epoch_ms", reference.capturedAtEpochMillis)
        } ?: JSONObject.NULL)

    private fun decodeTerrainPlan(value: JSONObject, schemaVersion: Int) = SurveyTerrainPlan(
        sourceName = value.getString("source_name"),
        sourceSha256 = value.getString("source_sha256"),
        sourceKind = if (schemaVersion >= 11) {
            runCatching {
                SurveyTerrainSourceKind.valueOf(
                    value.optString("source_kind", SurveyTerrainSourceKind.SURFACE_DSM.name),
                )
            }.getOrDefault(SurveyTerrainSourceKind.SURFACE_DSM)
        } else SurveyTerrainSourceKind.SURFACE_DSM,
        bareEarthBaseSha256 = if (schemaVersion >= 11 && !value.isNull("bare_earth_base_sha256")) {
            value.getString("bare_earth_base_sha256")
        } else null,
        epsg = value.getInt("epsg"),
        targetAglMeters = value.getDouble("target_agl_m"),
        takeoffTerrainElevationMeters = value.getDouble("takeoff_terrain_elevation_m"),
        sampleSpacingMeters = value.getDouble("sample_spacing_m"),
        minimumTerrainElevationMeters = value.getDouble("minimum_terrain_elevation_m"),
        maximumTerrainElevationMeters = value.getDouble("maximum_terrain_elevation_m"),
        minimumWaypointAltitudeMeters = value.getDouble("minimum_waypoint_altitude_m"),
        maximumWaypointAltitudeMeters = value.getDouble("maximum_waypoint_altitude_m"),
        realFlightVerified = value.optBoolean("real_flight_verified", false),
        takeoffReference = if (schemaVersion >= 11 && !value.isNull("takeoff_reference")) {
            value.getJSONObject("takeoff_reference").let { reference ->
                SurveyTerrainTakeoffReference(
                    point = decodePoint(reference.getJSONObject("point")),
                    source = SurveyTerrainTakeoffReferenceSource.valueOf(reference.getString("source")),
                    capturedAtEpochMillis = reference.getLong("captured_at_epoch_ms"),
                )
            }
        } else null,
    )

    private fun encodeCamera(camera: CameraProfile) = JSONObject()
        .put("id", camera.id)
        .put("image_width_px", camera.imageWidthPixels)
        .put("image_height_px", camera.imageHeightPixels)
        .put("horizontal_fov_deg", camera.horizontalFieldOfViewDegrees)
        .put("vertical_fov_deg", camera.verticalFieldOfViewDegrees)
        .put("minimum_capture_interval_s", camera.minimumCaptureIntervalSeconds)

    private fun decodeCamera(value: JSONObject) = CameraProfile(
        id = value.getString("id"),
        imageWidthPixels = value.getInt("image_width_px"),
        imageHeightPixels = value.getInt("image_height_px"),
        horizontalFieldOfViewDegrees = value.getDouble("horizontal_fov_deg"),
        verticalFieldOfViewDegrees = value.getDouble("vertical_fov_deg"),
        minimumCaptureIntervalSeconds = value.optDouble("minimum_capture_interval_s", 1.0),
    )

    private fun encodeConstraints(value: SurveyConstraints) = JSONObject()
        .put("altitude_agl_m", value.altitudeMetersAgl)
        .put("forward_overlap", value.forwardOverlap)
        .put("side_overlap", value.sideOverlap)
        .put("speed_mps", value.speedMetersPerSecond)
        .put("oblique_speed_mps", value.obliqueSpeedMetersPerSecond)
        .put("gimbal_pitch_deg", value.gimbalPitchDegrees)
        .put("route_heading_deg", value.routeHeadingDegrees)
        .put("crosshatch", value.crosshatch)
        .put("collection_mode", value.collectionMode.name)
        .put("oblique_gimbal_pitch_deg", value.obliqueGimbalPitchDegrees)
        .put("boundary_margin_m", value.boundaryMarginMeters)
        .put("altitude_mode", value.altitudeMode.name)
        .put("target_surface_to_takeoff_m", value.targetSurfaceToTakeoffMeters)
        .put("safe_takeoff_altitude_m", value.safeTakeoffAltitudeMeters)
        .put("takeoff_speed_mps", value.takeoffSpeedMetersPerSecond)
        .put("descent_speed_mps", value.descentSpeedMetersPerSecond)
        .put("takeoff_mode", value.takeoffMode.name)
        .put("start_point_mode", value.startPointMode.name)
        .put("completion_action", value.completionAction.name)
        .put("capture_trigger_mode", value.captureTriggerMode.name)
        .put("timed_capture_interval_s", value.timedCaptureIntervalSeconds)
        .put("oblique_forward_overlap", value.obliqueForwardOverlap)
        .put("oblique_side_overlap", value.obliqueSideOverlap)
        .put("oblique_heading_mode", value.obliqueHeadingMode.name)
        .put("enabled_capture_views", JSONArray().apply {
            value.enabledCaptureViews.forEach { put(it.name) }
        })

    private fun decodeConstraints(value: JSONObject, schemaVersion: Int): SurveyConstraints {
        val legacyCrosshatch = value.optBoolean("crosshatch", false)
        return SurveyConstraints(
            altitudeMetersAgl = value.getDouble("altitude_agl_m"),
            forwardOverlap = value.getDouble("forward_overlap"),
            sideOverlap = value.getDouble("side_overlap"),
            speedMetersPerSecond = value.getDouble("speed_mps"),
            obliqueSpeedMetersPerSecond = if (schemaVersion >= 12) {
                value.getDouble("oblique_speed_mps")
            } else {
                value.getDouble("speed_mps")
            },
            gimbalPitchDegrees = value.getDouble("gimbal_pitch_deg"),
            routeHeadingDegrees = value.getDouble("route_heading_deg"),
            crosshatch = legacyCrosshatch,
            collectionMode = if (schemaVersion >= 3) {
                SurveyCollectionMode.valueOf(value.getString("collection_mode"))
            } else if (legacyCrosshatch) {
                SurveyCollectionMode.CROSSHATCH_NADIR
            } else {
                SurveyCollectionMode.ORTHO
            },
            obliqueGimbalPitchDegrees = if (schemaVersion >= 3) {
                value.getDouble("oblique_gimbal_pitch_deg")
            } else {
                -45.0
            },
            // Schema 1 stored an inward safety margin. It cannot be reinterpreted
            // safely as DJI's outward margin, so legacy missions retain their
            // baked waypoints and start with outward margin disabled.
            boundaryMarginMeters = if (schemaVersion >= 2) value.getDouble("boundary_margin_m") else 0.0,
            altitudeMode = if (schemaVersion >= 4) {
                SurveyAltitudeMode.valueOf(value.getString("altitude_mode"))
            } else {
                SurveyAltitudeMode.ABOVE_TARGET_SURFACE
            },
            targetSurfaceToTakeoffMeters = if (schemaVersion >= 4) {
                value.getDouble("target_surface_to_takeoff_m")
            } else 0.0,
            safeTakeoffAltitudeMeters = if (schemaVersion >= 4) {
                value.getDouble("safe_takeoff_altitude_m")
            } else 30.0,
            takeoffSpeedMetersPerSecond = if (schemaVersion >= 4) {
                value.getDouble("takeoff_speed_mps")
            } else 3.0,
            descentSpeedMetersPerSecond = if (schemaVersion >= 13) {
                value.getDouble("descent_speed_mps")
            } else 2.0,
            takeoffMode = if (schemaVersion >= 6) {
                SurveyTakeoffMode.valueOf(value.getString("takeoff_mode"))
            } else SurveyTakeoffMode.MANUAL,
            startPointMode = if (schemaVersion >= 4) {
                SurveyStartPointMode.valueOf(value.getString("start_point_mode"))
            } else SurveyStartPointMode.AUTO_NEAREST,
            completionAction = if (schemaVersion >= 4) {
                SurveyCompletionAction.valueOf(value.getString("completion_action"))
            } else SurveyCompletionAction.RETURN_TO_HOME,
            captureTriggerMode = if (schemaVersion >= 4) {
                SurveyCaptureTriggerMode.valueOf(value.getString("capture_trigger_mode"))
            } else SurveyCaptureTriggerMode.DISTANCE,
            timedCaptureIntervalSeconds = if (schemaVersion >= 4) {
                value.getDouble("timed_capture_interval_s")
            } else 1.0,
            obliqueForwardOverlap = if (schemaVersion >= 4) {
                value.getDouble("oblique_forward_overlap")
            } else value.getDouble("forward_overlap"),
            obliqueSideOverlap = if (schemaVersion >= 4) {
                value.getDouble("oblique_side_overlap")
            } else value.getDouble("side_overlap"),
            obliqueHeadingMode = if (schemaVersion >= 10) {
                SurveyObliqueHeadingMode.valueOf(value.getString("oblique_heading_mode"))
            } else SurveyObliqueHeadingMode.TRACK_ROUTE,
            enabledCaptureViews = if (schemaVersion >= 8) {
                val array = value.getJSONArray("enabled_capture_views")
                (0 until array.length()).map {
                    SurveyCaptureView.valueOf(array.getString(it))
                }.toSet()
            } else STANDARD_SURVEY_CAPTURE_VIEWS,
        )
    }

    private fun encodePoint(point: GeoPoint) = JSONObject()
        .put("latitude", point.latitude)
        .put("longitude", point.longitude)
        .put("altitude_m", point.altitudeMeters)

    private fun decodePoint(value: JSONObject) = GeoPoint(
        latitude = value.getDouble("latitude"),
        longitude = value.getDouble("longitude"),
        altitudeMeters = value.getDouble("altitude_m"),
    )

    private fun encodeWaypoint(value: SurveyWaypoint) = JSONObject()
        .put("point", encodePoint(value.point))
        .put("heading_deg", value.headingDegrees)
        .put("gimbal_pitch_deg", value.gimbalPitchDegrees)
        .put("kind", value.kind.name)
        .put("capture_action", value.captureAction.name)
        .put("capture_interval_m", value.captureIntervalMeters ?: JSONObject.NULL)
        .put("pass_index", value.passIndex)
        .put("capture_view", value.captureView.name)

    private fun decodeWaypoint(value: JSONObject, schemaVersion: Int) = SurveyWaypoint(
        point = decodePoint(value.getJSONObject("point")),
        headingDegrees = value.getDouble("heading_deg"),
        gimbalPitchDegrees = value.getDouble("gimbal_pitch_deg"),
        kind = SurveyWaypointKind.valueOf(value.getString("kind")),
        captureAction = CaptureAction.valueOf(value.getString("capture_action")),
        captureIntervalMeters = if (value.isNull("capture_interval_m")) null
            else value.getDouble("capture_interval_m"),
        passIndex = value.getInt("pass_index"),
        captureView = if (schemaVersion >= 3) {
            SurveyCaptureView.valueOf(value.getString("capture_view"))
        } else {
            SurveyCaptureView.NADIR
        },
    )

    private fun <T> decodeArray(array: JSONArray, decoder: (JSONObject) -> T): List<T> =
        (0 until array.length()).map { decoder(array.getJSONObject(it)) }

    private fun decodeStringArray(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun decodeIntArray(array: JSONArray): List<Int> =
        (0 until array.length()).map(array::getInt)
}
