package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

data class SurveyTerrainSafetyReport(
    val controlPointCount: Int,
    val minimumTerrainElevationMeters: Double,
    val maximumTerrainElevationMeters: Double,
    val minimumWaypointAltitudeMeters: Double,
    val maximumWaypointAltitudeMeters: Double,
    val maximumRequiredVerticalSpeedMetersPerSecond: Double,
)

data class SurveyTerrainPlanResult(
    val mission: SurveyMission,
    val safety: SurveyTerrainSafetyReport,
)

object SurveyTerrainPlanner {
    /** Dense enough to detect ordinary building footprints after DSM interpolation. */
    const val DEFAULT_SAMPLE_SPACING_METERS = 3.0
    const val MAX_TERRAIN_CONTROL_POINTS = 20_000
    const val AIRCRAFT_CLEARANCE_RADIUS_METERS = 2.0

    private data class TerrainControl(
        val point: GeoPoint,
        val template: SurveyWaypoint,
        val terrainElevationMeters: Double,
        var flightAltitudeMeters: Double,
    )

    fun apply(
        mission: SurveyMission,
        terrain: TerrainElevationSource,
        takeoffReference: SurveyTerrainTakeoffReference,
        sourceSha256: String,
        sampleSpacingMeters: Double = DEFAULT_SAMPLE_SPACING_METERS,
        sourceKind: SurveyTerrainSourceKind = SurveyTerrainSourceKind.SURFACE_DSM,
        bareEarthBaseSha256: String? = null,
        context: Context? = null,
    ): SurveyTerrainPlanResult {
        require(mission.constraints.altitudeMode == SurveyAltitudeMode.ABOVE_TARGET_SURFACE) {
            context?.getString(R.string.terrain_altitude_mode_target_surface_required)
                ?: "DSM terrain following requires altitude relative to the target surface"
        }
        require(sourceSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            context?.getString(R.string.dsm_sha_invalid) ?: "Invalid DSM SHA-256"
        }
        require(bareEarthBaseSha256 == null || bareEarthBaseSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            context?.getString(R.string.bare_earth_dem_sha_invalid) ?: "Invalid bare-earth DEM SHA-256"
        }
        require(sourceKind != SurveyTerrainSourceKind.BARE_EARTH ||
            bareEarthBaseSha256.equals(sourceSha256, ignoreCase = true)) {
            context?.getString(R.string.bare_earth_dem_sha_must_match)
                ?: "Bare-earth DEM mission base SHA-256 must match the current data source"
        }
        require(sampleSpacingMeters in 2.0..50.0) {
            context?.getString(R.string.dsm_sample_spacing_range) ?: "DSM sample spacing must be 2–50 m"
        }
        val takeoffPoint = takeoffReference.point
        val takeoffTerrain = terrain.elevationMeters(takeoffPoint.latitude, takeoffPoint.longitude)
        val elevations = mutableListOf<Double>()
        fun clearanceElevation(point: GeoPoint): Double {
            val latitudeOffset = AIRCRAFT_CLEARANCE_RADIUS_METERS / 111_132.0
            val longitudeOffset = AIRCRAFT_CLEARANCE_RADIUS_METERS /
                (111_320.0 * cos(Math.toRadians(point.latitude)))
            val samples = listOf(
                point,
                point.copy(latitude = point.latitude + latitudeOffset),
                point.copy(latitude = point.latitude - latitudeOffset),
                point.copy(longitude = point.longitude + longitudeOffset),
                point.copy(longitude = point.longitude - longitudeOffset),
            ).map { terrain.elevationMeters(it.latitude, it.longitude) }
            elevations += samples
            return samples.max()
        }
        val safeVertical = SurveyWaypointFollower.MAX_VERTICAL_SPEED_METERS_PER_SECOND * 0.9
        val maximumSlope = safeVertical / mission.constraints.maximumSurveySpeedMetersPerSecond
        val passes = mission.surveyPasses().map { pass ->
            val path = densify(pass.waypoints.map { it.point }, sampleSpacingMeters)
            path.mapIndexed { index, point ->
                val elevation = clearanceElevation(point)
                val template = when (index) {
                    0 -> pass.start
                    path.lastIndex -> pass.end
                    else -> pass.start.copy(
                        kind = SurveyWaypointKind.TRANSIT,
                        captureAction = CaptureAction.NONE,
                        captureIntervalMeters = null,
                    )
                }
                TerrainControl(
                    point, template, elevation,
                    mission.constraints.altitudeMetersAgl + elevation - takeoffTerrain,
                )
            }.toMutableList()
        }
        require(passes.sumOf { it.size } <= MAX_TERRAIN_CONTROL_POINTS) {
            context?.getString(R.string.dsm_control_points_exceeded, MAX_TERRAIN_CONTROL_POINTS)
                ?: "DSM route control points exceed $MAX_TERRAIN_CONTROL_POINTS; reduce the survey area or pass density"
        }

        fun maximumTransitClearance(start: GeoPoint, end: GeoPoint): Double {
            return densify(listOf(start, end), sampleSpacingMeters).maxOf { point ->
                val elevation = clearanceElevation(point)
                mission.constraints.altitudeMetersAgl + elevation - takeoffTerrain
            }
        }

        // Transits are flown with interval capture off. Raising both endpoints
        // above the sampled peak keeps the straight transit clear of buildings.
        val first = passes.first().first()
        first.flightAltitudeMeters = maxOf(first.flightAltitudeMeters,
            maximumTransitClearance(takeoffPoint, first.point))
        passes.zipWithNext().forEach { (before, after) ->
            val clearance = maximumTransitClearance(before.last().point, after.first().point)
            before.last().flightAltitudeMeters = maxOf(before.last().flightAltitudeMeters, clearance)
            after.first().flightAltitudeMeters = maxOf(after.first().flightAltitudeMeters, clearance)
        }
        if (mission.constraints.completionAction == SurveyCompletionAction.RETURN_TO_HOME) {
            // Executor's RTH cruise altitude is max(safeTakeoff, first altitude).
            first.flightAltitudeMeters = maxOf(first.flightAltitudeMeters,
                maximumTransitClearance(passes.last().last().point, takeoffPoint))
        }

        // Build the minimum safe climb/descent envelope. A high roof propagates
        // backwards (early climb) and forwards (controlled descent).
        passes.forEach { controls ->
            for (index in 1 until controls.size) {
                val distance = horizontalDistance(controls[index - 1].point, controls[index].point)
                controls[index].flightAltitudeMeters = maxOf(
                    controls[index].flightAltitudeMeters,
                    controls[index - 1].flightAltitudeMeters - maximumSlope * distance,
                )
            }
            for (index in controls.lastIndex - 1 downTo 0) {
                val distance = horizontalDistance(controls[index].point, controls[index + 1].point)
                controls[index].flightAltitudeMeters = maxOf(
                    controls[index].flightAltitudeMeters,
                    controls[index + 1].flightAltitudeMeters - maximumSlope * distance,
                )
            }
        }

        val generated = mutableListOf<SurveyWaypoint>()
        var maximumVerticalSpeed = 0.0
        passes.forEach { controls ->
            controls.forEach { control ->
                val altitude = control.flightAltitudeMeters
                require(altitude in SurveySimulatorGate.MIN_MISSION_ALTITUDE_METERS..
                    SurveySimulatorGate.MAX_MISSION_ALTITUDE_METERS) {
                    context?.getString(R.string.dsm_waypoint_altitude_out_of_range, altitude)
                        ?: "DSM waypoint relative altitude %.1f m is outside 5–120 m".format(altitude)
                }
                val waypoint = control.template.copy(
                    point = control.point.copy(altitudeMeters = altitude))
                generated.lastOrNull()?.takeIf { it.passIndex == waypoint.passIndex }?.let { previous ->
                    val horizontal = horizontalDistance(previous.point, waypoint.point)
                    if (horizontal > 0.1) {
                        maximumVerticalSpeed = maxOf(maximumVerticalSpeed,
                            abs(waypoint.point.altitudeMeters - previous.point.altitudeMeters) /
                                horizontal * mission.constraints.maximumSurveySpeedMetersPerSecond)
                    }
                }
                generated += waypoint
            }
        }
        check(maximumVerticalSpeed <= safeVertical + 1e-6) {
            context?.getString(R.string.dsm_vertical_speed_envelope_error)
                ?: "DSM vertical-speed envelope calculation error"
        }
        val minAltitude = generated.minOf { it.point.altitudeMeters }
        val maxAltitude = generated.maxOf { it.point.altitudeMeters }
        val metadata = SurveyTerrainPlan(
            sourceName = terrain.info.displayName,
            sourceSha256 = sourceSha256.lowercase(),
            sourceKind = sourceKind,
            bareEarthBaseSha256 = bareEarthBaseSha256?.lowercase(),
            epsg = terrain.info.epsg,
            targetAglMeters = mission.constraints.altitudeMetersAgl,
            takeoffTerrainElevationMeters = takeoffTerrain,
            sampleSpacingMeters = sampleSpacingMeters,
            minimumTerrainElevationMeters = elevations.min(),
            maximumTerrainElevationMeters = elevations.max(),
            minimumWaypointAltitudeMeters = minAltitude,
            maximumWaypointAltitudeMeters = maxAltitude,
            realFlightVerified = false,
            takeoffReference = takeoffReference,
        )
        val resultMission = mission.copy(waypoints = generated, terrainPlan = metadata)
        return SurveyTerrainPlanResult(resultMission, SurveyTerrainSafetyReport(
            controlPointCount = generated.size,
            minimumTerrainElevationMeters = metadata.minimumTerrainElevationMeters,
            maximumTerrainElevationMeters = metadata.maximumTerrainElevationMeters,
            minimumWaypointAltitudeMeters = minAltitude,
            maximumWaypointAltitudeMeters = maxAltitude,
            maximumRequiredVerticalSpeedMetersPerSecond = maximumVerticalSpeed,
        ))
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun densify(points: List<GeoPoint>, spacing: Double): List<GeoPoint> {
        val result = mutableListOf(points.first())
        points.zipWithNext().forEach { (start, end) ->
            val pieces = maxOf(1, ceil(horizontalDistance(start, end) / spacing).toInt())
            for (index in 1..pieces) {
                val ratio = index.toDouble() / pieces
                result += GeoPoint(
                    start.latitude + (end.latitude - start.latitude) * ratio,
                    start.longitude + (end.longitude - start.longitude) * ratio,
                    start.altitudeMeters + (end.altitudeMeters - start.altitudeMeters) * ratio,
                )
            }
        }
        return result
    }

    internal fun horizontalDistance(a: GeoPoint, b: GeoPoint): Double {
        val meanLatitude = (a.latitude + b.latitude) / 2.0
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 * cos(Math.toRadians(meanLatitude))
        return hypot(north, east)
    }
}
