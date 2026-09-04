package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class SurveyTerrainTakeoffReferenceSource {
    HOME_LOCATION,
    AIRCRAFT_LOCATION,
}

/** WGS-84 point whose terrain elevation defines every takeoff-relative terrain waypoint. */
data class SurveyTerrainTakeoffReference(
    val point: GeoPoint,
    val source: SurveyTerrainTakeoffReferenceSource,
    val capturedAtEpochMillis: Long,
) {
    init {
        require(capturedAtEpochMillis > 0L) { "takeoff reference timestamp must be positive" }
    }
}

data class SurveyTerrainTakeoffVerification(
    val valid: Boolean,
    val distanceMeters: Double?,
    val terrainElevationDifferenceMeters: Double?,
    val reason: String?,
)

/** Safety check run again against the live Home position before a terrain mission can execute. */
object SurveyTerrainTakeoffReferencePolicy {
    const val MAX_REFERENCE_DISTANCE_METERS = 15.0
    const val MAX_TERRAIN_ELEVATION_DIFFERENCE_METERS = 3.0

    fun verify(
        plan: SurveyTerrainPlan,
        currentHome: GeoPoint?,
        terrain: TerrainElevationSource?,
        context: Context? = null,
    ): SurveyTerrainTakeoffVerification {
        val reference = plan.takeoffReference ?: return invalid(text(
            context,
            R.string.terrain_legacy_mission_takeoff_reference_missing,
            "Legacy terrain mission is missing a takeoff reference; regenerate the route",
        ))
        val home = currentHome ?: return invalid(text(
            context,
            R.string.terrain_home_gps_unavailable,
            "Current Home/GPS is unavailable; cannot verify the terrain takeoff reference",
        ))
        val source = terrain ?: return invalid(text(
            context,
            R.string.terrain_generation_elevation_source_missing,
            "The elevation data used to generate the route is not loaded",
        ))
        val distance = distanceMeters(reference.point, home)
        if (distance > MAX_REFERENCE_DISTANCE_METERS) {
            return SurveyTerrainTakeoffVerification(
                false,
                distance,
                null,
                context?.getString(
                    R.string.terrain_home_reference_too_far,
                    distance,
                    MAX_REFERENCE_DISTANCE_METERS,
                ) ?: "Current Home is %.1f m from the generation reference, above %.0f m; regenerate at the actual takeoff point".format(
                        distance,
                        MAX_REFERENCE_DISTANCE_METERS,
                    ),
            )
        }
        val currentElevation = runCatching {
            source.elevationMeters(home.latitude, home.longitude)
        }.getOrElse {
            return SurveyTerrainTakeoffVerification(
                false,
                distance,
                null,
                context?.getString(R.string.terrain_home_outside_elevation_coverage, it.message.orEmpty())
                    ?: "Current Home is outside valid elevation coverage: ${it.message}",
            )
        }
        if (!currentElevation.isFinite()) {
            return SurveyTerrainTakeoffVerification(
                false,
                distance,
                null,
                text(context, R.string.terrain_home_elevation_invalid, "Current Home elevation is invalid"),
            )
        }
        val difference = kotlin.math.abs(currentElevation - plan.takeoffTerrainElevationMeters)
        if (difference > MAX_TERRAIN_ELEVATION_DIFFERENCE_METERS) {
            return SurveyTerrainTakeoffVerification(
                false,
                distance,
                difference,
                context?.getString(
                    R.string.terrain_home_elevation_difference_too_large,
                    difference,
                    MAX_TERRAIN_ELEVATION_DIFFERENCE_METERS,
                ) ?: "Current Home elevation differs from the generation reference by %.1f m, above %.0f m; regenerate the route".format(
                        difference,
                        MAX_TERRAIN_ELEVATION_DIFFERENCE_METERS,
                    ),
            )
        }
        return SurveyTerrainTakeoffVerification(true, distance, difference, null)
    }

    private fun invalid(reason: String) = SurveyTerrainTakeoffVerification(false, null, null, reason)

    private fun text(context: Context?, resourceId: Int, fallback: String): String =
        context?.getString(resourceId) ?: fallback

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val earthRadiusMeters = 6_371_000.0
        val latitudeA = Math.toRadians(a.latitude)
        val latitudeB = Math.toRadians(b.latitude)
        val deltaLatitude = latitudeB - latitudeA
        val deltaLongitude = Math.toRadians(b.longitude - a.longitude)
        val haversine = sin(deltaLatitude / 2.0) * sin(deltaLatitude / 2.0) +
            cos(latitudeA) * cos(latitudeB) *
            sin(deltaLongitude / 2.0) * sin(deltaLongitude / 2.0)
        return 2.0 * earthRadiusMeters * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }
}
