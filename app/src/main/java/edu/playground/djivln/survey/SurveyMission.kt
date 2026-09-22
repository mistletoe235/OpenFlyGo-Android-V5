package edu.playground.djivln.survey

import java.util.UUID
import kotlin.math.cos

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be in [-90, 90]" }
        require(longitude in -180.0..180.0) { "longitude must be in [-180, 180]" }
        require(altitudeMeters.isFinite()) { "altitude must be finite" }
    }
}

data class CameraProfile(
    val id: String,
    val imageWidthPixels: Int,
    val imageHeightPixels: Int,
    val horizontalFieldOfViewDegrees: Double,
    val verticalFieldOfViewDegrees: Double,
    /** Fastest supported JPEG interval for route-planning feasibility checks. */
    val minimumCaptureIntervalSeconds: Double = 1.0,
) {
    init {
        require(id.isNotBlank()) { "camera id must not be blank" }
        require(imageWidthPixels > 0 && imageHeightPixels > 0) { "image dimensions must be positive" }
        require(horizontalFieldOfViewDegrees in 1.0..179.0) { "horizontal FOV must be in [1, 179]" }
        require(verticalFieldOfViewDegrees in 1.0..179.0) { "vertical FOV must be in [1, 179]" }
        require(minimumCaptureIntervalSeconds > 0.0 && minimumCaptureIntervalSeconds.isFinite()) {
            "minimum capture interval must be positive"
        }
    }

    companion object {
        /** Conservative fallback only; production planning should use DjiCameraProfileCatalog. */
        val GENERIC_4_BY_3 = CameraProfile(
            id = "generic-unverified-photo-4x3",
            imageWidthPixels = 4000,
            imageHeightPixels = 3000,
            horizontalFieldOfViewDegrees = 70.0,
            verticalFieldOfViewDegrees = 55.0,
            minimumCaptureIntervalSeconds = 2.0,
        )

        /** Kept for schema/test compatibility; live planning no longer assumes Mini 2. */
        val DJI_MINI_2: CameraProfile
            get() = DjiCameraProfileCatalog.resolve("DJI_MINI_2").profile
    }
}

enum class SurveyCollectionMode {
    ORTHO,
    /** Retained only so schema 1/2 missions keep their original meaning. */
    CROSSHATCH_NADIR,
    OBLIQUE_FIVE_DIRECTION,
}

enum class SurveyAltitudeMode {
    /** Mission height is expressed above the surveyed target surface. */
    ABOVE_TARGET_SURFACE,
    /** Mission height is expressed directly above the takeoff point. */
    RELATIVE_TO_TAKEOFF,
}

enum class SurveyStartPointMode {
    AUTO_NEAREST,
    /** Legacy/schema name for route corner 1. */
    FIRST_ROUTE_START,
    ROUTE_CORNER_2,
    ROUTE_CORNER_3,
    ROUTE_CORNER_4,
    /** Retained for imported experimental missions; resolved as AUTO_NEAREST. */
    CUSTOM,
}

enum class SurveyCompletionAction {
    RETURN_TO_HOME,
    HOVER,
    RETURN_TO_ROUTE_START,
}

enum class SurveyCaptureTriggerMode {
    DISTANCE,
    TIME,
}

enum class SurveyTakeoffMode {
    /** Pilot remains responsible for takeoff; route execution only starts once flying. */
    MANUAL,
    /** Debug/Simulator only: request DJI takeoff, verify stable flight, then arm the route. */
    AUTO_SIMULATOR_ONLY,
}

enum class SurveyObliqueHeadingMode {
    /** Safer default: aircraft nose follows each flight line, avoiding sustained reverse flight. */
    TRACK_ROUTE,
    /** Keeps a fixed horizontal camera direction; reverse or lateral flight may be required. */
    FIXED_CAPTURE_DIRECTION,
}

data class SurveyConstraints(
    /** Camera height above the target surface, or takeoff point when altitudeMode says so. */
    val altitudeMetersAgl: Double = 60.0,
    val forwardOverlap: Double = 0.80,
    val sideOverlap: Double = 0.70,
    /** Nadir/orthophoto route speed. */
    val speedMetersPerSecond: Double = 3.0,
    /** Oblique route speed; kept separate because oblique capture spacing is usually larger. */
    val obliqueSpeedMetersPerSecond: Double = speedMetersPerSecond,
    val gimbalPitchDegrees: Double = -90.0,
    val routeHeadingDegrees: Double = 0.0,
    val crosshatch: Boolean = false,
    val collectionMode: SurveyCollectionMode = if (crosshatch) {
        SurveyCollectionMode.CROSSHATCH_NADIR
    } else {
        SurveyCollectionMode.ORTHO
    },
    val obliqueGimbalPitchDegrees: Double = -45.0,
    /** Positive DJI-style boundary margin that expands the photographed ROI. */
    val boundaryMarginMeters: Double = 0.0,
    val altitudeMode: SurveyAltitudeMode = SurveyAltitudeMode.ABOVE_TARGET_SURFACE,
    /** Target surface elevation relative to takeoff; may be negative. */
    val targetSurfaceToTakeoffMeters: Double = 0.0,
    val safeTakeoffAltitudeMeters: Double = 30.0,
    /** Maximum upward velocity used by custom/HIL execution. */
    val takeoffSpeedMetersPerSecond: Double = 3.0,
    /** Maximum downward velocity used by custom/HIL execution. */
    val descentSpeedMetersPerSecond: Double = 2.0,
    val takeoffMode: SurveyTakeoffMode = SurveyTakeoffMode.MANUAL,
    val startPointMode: SurveyStartPointMode = SurveyStartPointMode.AUTO_NEAREST,
    val completionAction: SurveyCompletionAction = SurveyCompletionAction.RETURN_TO_HOME,
    val captureTriggerMode: SurveyCaptureTriggerMode = SurveyCaptureTriggerMode.DISTANCE,
    val timedCaptureIntervalSeconds: Double = 1.0,
    val obliqueForwardOverlap: Double = 0.70,
    val obliqueSideOverlap: Double = 0.60,
    val obliqueHeadingMode: SurveyObliqueHeadingMode = SurveyObliqueHeadingMode.TRACK_ROUTE,
    /** Capture-view route groups generated for an oblique mission. */
    val enabledCaptureViews: Set<SurveyCaptureView> = STANDARD_SURVEY_CAPTURE_VIEWS,
) {
    init {
        require(altitudeMetersAgl > 0.0 && altitudeMetersAgl.isFinite()) { "altitude must be positive" }
        require(forwardOverlap in 0.0..0.95) { "forward overlap must be in [0, 0.95]" }
        require(sideOverlap in 0.0..0.95) { "side overlap must be in [0, 0.95]" }
        require(speedMetersPerSecond > 0.0 && speedMetersPerSecond.isFinite()) { "speed must be positive" }
        require(obliqueSpeedMetersPerSecond > 0.0 && obliqueSpeedMetersPerSecond.isFinite()) {
            "oblique speed must be positive"
        }
        require(gimbalPitchDegrees in -90.0..30.0) { "gimbal pitch is outside the supported range" }
        require(obliqueGimbalPitchDegrees in -90.0..30.0) {
            "oblique gimbal pitch is outside the supported range"
        }
        require(routeHeadingDegrees.isFinite()) { "route heading must be finite" }
        require(boundaryMarginMeters >= 0.0 && boundaryMarginMeters.isFinite()) {
            "boundary margin must be non-negative"
        }
        require(targetSurfaceToTakeoffMeters.isFinite()) { "target surface offset must be finite" }
        require(safeTakeoffAltitudeMeters > 0.0 && safeTakeoffAltitudeMeters.isFinite()) {
            "safe takeoff altitude must be positive"
        }
        require(takeoffSpeedMetersPerSecond > 0.0 && takeoffSpeedMetersPerSecond.isFinite()) {
            "takeoff speed must be positive"
        }
        require(descentSpeedMetersPerSecond > 0.0 && descentSpeedMetersPerSecond.isFinite()) {
            "descent speed must be positive"
        }
        require(timedCaptureIntervalSeconds > 0.0 && timedCaptureIntervalSeconds.isFinite()) {
            "timed capture interval must be positive"
        }
        require(obliqueForwardOverlap in 0.0..0.95) { "oblique forward overlap must be in [0, 0.95]" }
        require(obliqueSideOverlap in 0.0..0.95) { "oblique side overlap must be in [0, 0.95]" }
        require(collectionMode != SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION || enabledCaptureViews.isNotEmpty()) {
            "an oblique mission must enable at least one capture view"
        }
        require(effectiveFlightAltitudeMeters > 0.0) {
            "effective flight altitude relative to takeoff must be positive"
        }
    }

    /** Planned camera altitude in the takeoff-relative frame used by waypoints. */
    val effectiveFlightAltitudeMeters: Double
        get() = if (altitudeMode == SurveyAltitudeMode.ABOVE_TARGET_SURFACE) {
            altitudeMetersAgl + targetSurfaceToTakeoffMeters
        } else {
            altitudeMetersAgl
        }

    fun speedForCaptureView(captureView: SurveyCaptureView): Double = when (captureView) {
        SurveyCaptureView.NADIR -> speedMetersPerSecond
        else -> obliqueSpeedMetersPerSecond
    }

    val maximumSurveySpeedMetersPerSecond: Double
        get() = if (collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION) {
            enabledCaptureViews.maxOf(::speedForCaptureView)
        } else {
            speedMetersPerSecond
        }
}

enum class SurveyWaypointKind {
    TRANSIT,
    PASS_START,
    PASS_END,
    CAPTURE_POINT,
}

enum class CaptureAction {
    NONE,
    START_DISTANCE_INTERVAL,
    STOP_DISTANCE_INTERVAL,
    CAPTURE_ON_REACH,
}

enum class SurveyCaptureView {
    NADIR,
    FORWARD_OBLIQUE,
    BACKWARD_OBLIQUE,
    LEFT_OBLIQUE,
    RIGHT_OBLIQUE,
    LOCAL_OBLIQUE,
}

val STANDARD_SURVEY_CAPTURE_VIEWS: Set<SurveyCaptureView> = setOf(
    SurveyCaptureView.NADIR,
    SurveyCaptureView.FORWARD_OBLIQUE,
    SurveyCaptureView.BACKWARD_OBLIQUE,
    SurveyCaptureView.LEFT_OBLIQUE,
    SurveyCaptureView.RIGHT_OBLIQUE,
)

data class SurveyWaypoint(
    val point: GeoPoint,
    val headingDegrees: Double,
    val gimbalPitchDegrees: Double,
    val kind: SurveyWaypointKind,
    val captureAction: CaptureAction,
    val captureIntervalMeters: Double? = null,
    val passIndex: Int,
    val captureView: SurveyCaptureView = SurveyCaptureView.NADIR,
) {
    init {
        require(headingDegrees.isFinite()) { "heading must be finite" }
        require(passIndex >= 0) { "pass index must be non-negative" }
        if (captureAction == CaptureAction.START_DISTANCE_INTERVAL) {
            require(captureIntervalMeters != null && captureIntervalMeters > 0.0) {
                "capture interval is required when interval capture starts"
            }
        }
    }
}

data class SurveyPassWaypoints(
    val firstWaypointIndex: Int,
    val lastWaypointIndex: Int,
    val waypoints: List<SurveyWaypoint>,
) {
    val start: SurveyWaypoint get() = waypoints.first()
    val end: SurveyWaypoint get() = waypoints.last()
    val isPointCapture: Boolean
        get() = waypoints.size == 1 && start.kind == SurveyWaypointKind.CAPTURE_POINT &&
            start.captureAction == CaptureAction.CAPTURE_ON_REACH
    val isTransitOnly: Boolean
        get() = waypoints.all {
            it.kind == SurveyWaypointKind.TRANSIT && it.captureAction == CaptureAction.NONE
        }
}

data class ActiveMappingTarget(
    val latitude: Double,
    val longitude: Double,
    val absoluteAltitudeMeters: Double,
)

data class ActiveMappingRegionMetadata(
    val regionId: String,
    val priority: Int,
    val kind: String,
    val riskScore: Double,
    val reasons: List<String> = emptyList(),
    val targetWgs84: ActiveMappingTarget? = null,
    val passIndices: List<Int> = emptyList(),
    val suggestedSurveyPhotos: Int = 0,
)

data class ActiveMappingPassMetadata(
    val passIndex: Int,
    val regionId: String,
    val role: String,
    val captureRole: String,
    val source: String,
    val requiredForReconstructionBridge: Boolean,
)

data class ActiveMappingMetadata(
    val schemaVersion: Int = 1,
    val selectionMethod: String,
    val groundTruthUsed: Boolean,
    val gsUsedForSelection: Boolean,
    val ordinaryGpsUsed: Boolean,
    val sourceCaptureCount: Int,
    val surveyCaptureCount: Int,
    val bridgeCaptureCount: Int,
    val sourceEstimatedRouteDistanceMeters: Double,
    val regions: List<ActiveMappingRegionMetadata> = emptyList(),
    val passes: List<ActiveMappingPassMetadata> = emptyList(),
) {
    init {
        require(schemaVersion >= 1) { "active mapping schema must be positive" }
        require(selectionMethod.isNotBlank()) { "active mapping selection method must not be blank" }
        require(sourceCaptureCount >= 0 && surveyCaptureCount >= 0 && bridgeCaptureCount >= 0) {
            "active mapping capture counts must be non-negative"
        }
        require(sourceEstimatedRouteDistanceMeters >= 0.0 && sourceEstimatedRouteDistanceMeters.isFinite()) {
            "active mapping source route distance must be non-negative"
        }
    }
}

data class SurveyTerrainPlan(
    val sourceName: String,
    val sourceSha256: String,
    val epsg: Int,
    val targetAglMeters: Double,
    val takeoffTerrainElevationMeters: Double,
    val sampleSpacingMeters: Double,
    val minimumTerrainElevationMeters: Double,
    val maximumTerrainElevationMeters: Double,
    val minimumWaypointAltitudeMeters: Double,
    val maximumWaypointAltitudeMeters: Double,
    val realFlightVerified: Boolean = false,
    val takeoffReference: SurveyTerrainTakeoffReference? = null,
    val sourceKind: SurveyTerrainSourceKind = SurveyTerrainSourceKind.SURFACE_DSM,
    val bareEarthBaseSha256: String? = null,
)

data class SurveyMission(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val coordinateFrame: String = "WGS84",
    val cameraProfile: CameraProfile,
    val constraints: SurveyConstraints,
    val roi: List<GeoPoint>,
    val waypoints: List<SurveyWaypoint>,
    val estimatedPathMeters: Double,
    val estimatedPhotoCount: Int,
    val estimatedFlightSeconds: Double,
    val terrainPlan: SurveyTerrainPlan? = null,
    val activeMapping: ActiveMappingMetadata? = null,
    val recaptureFlightMode: RecaptureFlightMode = RecaptureFlightMode.STOP_AND_CAPTURE,
) {
    init {
        require(recaptureFlightMode == RecaptureFlightMode.STOP_AND_CAPTURE || activeMapping != null) {
            "continuous capture is only supported for active recapture missions"
        }
        require(id.isNotBlank()) { "mission id must not be blank" }
        require(name.isNotBlank()) { "mission name must not be blank" }
        require(roi.size >= 3) { "mission ROI must contain at least three points" }
        require(estimatedPathMeters >= 0.0) { "estimated path must be non-negative" }
        require(estimatedPhotoCount >= 0) { "estimated photo count must be non-negative" }
        require(estimatedFlightSeconds >= 0.0) { "estimated duration must be non-negative" }
        require(waypoints.isNotEmpty()) { "mission must contain survey waypoints" }
        surveyPasses().forEachIndexed { pairIndex, pass ->
            val start = pass.start
            val end = pass.end
            if (pass.isPointCapture) {
                require(start.captureIntervalMeters == null) {
                    "point capture $pairIndex must not define a capture interval"
                }
                return@forEachIndexed
            }
            if (pass.isTransitOnly) {
                require(pass.waypoints.all { it.captureIntervalMeters == null }) {
                    "transit unit $pairIndex must not define a capture interval"
                }
                return@forEachIndexed
            }
            require(pass.waypoints.size >= 2 &&
                start.kind == SurveyWaypointKind.PASS_START &&
                start.captureAction == CaptureAction.START_DISTANCE_INTERVAL &&
                end.kind == SurveyWaypointKind.PASS_END &&
                end.captureAction == CaptureAction.STOP_DISTANCE_INTERVAL
            ) { "survey pass $pairIndex must contain a capture start and end, or one point capture" }
            require(start.passIndex == end.passIndex && start.captureView == end.captureView) {
                "survey pass $pairIndex endpoints must share pass index and capture view"
            }
            require(pass.waypoints.drop(1).dropLast(1).all {
                it.kind == SurveyWaypointKind.TRANSIT &&
                    it.captureAction == CaptureAction.NONE &&
                    it.passIndex == start.passIndex && it.captureView == start.captureView
            }) { "survey pass $pairIndex contains an invalid terrain control point" }
        }
    }
}

/** Groups interval passes, DSM passes containing control points, and schema 11 point captures. */
fun SurveyMission.surveyPasses(): List<SurveyPassWaypoints> {
    val result = mutableListOf<SurveyPassWaypoints>()
    var first = 0
    while (first < waypoints.size) {
        val passIndex = waypoints[first].passIndex
        var last = first
        while (last + 1 < waypoints.size && waypoints[last + 1].passIndex == passIndex) last++
        val group = waypoints.subList(first, last + 1)
        require(group.size >= 2 || (group.size == 1 && (
            group.first().kind == SurveyWaypointKind.CAPTURE_POINT &&
                group.first().captureAction == CaptureAction.CAPTURE_ON_REACH ||
                group.first().kind == SurveyWaypointKind.TRANSIT &&
                group.first().captureAction == CaptureAction.NONE
            ))
        ) { "survey pass $passIndex is incomplete" }
        result += SurveyPassWaypoints(first, last, group)
        first = last + 1
    }
    require(result.map { it.start.passIndex }.distinct().size == result.size) {
        "survey pass indices must be contiguous groups"
    }
    return result
}

internal data class LocalPoint(val eastMeters: Double, val northMeters: Double)

internal data class LocalFrame(val origin: GeoPoint) {
    private val metersPerDegreeLatitude = 111_132.0
    private val metersPerDegreeLongitude = 111_320.0 * cos(Math.toRadians(origin.latitude))

    fun toLocal(point: GeoPoint): LocalPoint = LocalPoint(
        eastMeters = (point.longitude - origin.longitude) * metersPerDegreeLongitude,
        northMeters = (point.latitude - origin.latitude) * metersPerDegreeLatitude,
    )

    fun toGeo(point: LocalPoint, altitudeMeters: Double): GeoPoint = GeoPoint(
        latitude = origin.latitude + point.northMeters / metersPerDegreeLatitude,
        longitude = origin.longitude + point.eastMeters / metersPerDegreeLongitude,
        altitudeMeters = altitudeMeters,
    )
}
