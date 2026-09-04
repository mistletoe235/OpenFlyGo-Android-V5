package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import kotlin.math.cos

/** Deterministic nearby missions for debug-device regression; never starts execution. */
object SurveyRegressionMissionFactory {
    @JvmStatic
    fun create(center: GeoPoint, fiveDirection: Boolean, context: Context? = null): SurveyMission {
        require(center.latitude.isFinite() && center.longitude.isFinite())
        val halfNorthMeters = 25.0
        val halfEastMeters = 20.0
        val latitudeDelta = halfNorthMeters / 111_132.0
        val longitudeDelta = halfEastMeters /
            (111_320.0 * cos(Math.toRadians(center.latitude)))
        val roi = listOf(
            GeoPoint(center.latitude + latitudeDelta, center.longitude - longitudeDelta),
            GeoPoint(center.latitude + latitudeDelta, center.longitude + longitudeDelta),
            GeoPoint(center.latitude - latitudeDelta, center.longitude + longitudeDelta),
            GeoPoint(center.latitude - latitudeDelta, center.longitude - longitudeDelta),
        )
        val constraints = SurveyParameterPolicy.createConstraints(
            altitudeMetersAgl = 30.0,
            routeHeadingDegrees = 0.0,
            forwardOverlapPercent = 80.0,
            sideOverlapPercent = 70.0,
            speedMetersPerSecond = 2.0,
            gimbalPitchDegrees = if (fiveDirection) -45.0 else -90.0,
            boundaryMarginMeters = 3.0,
            obliqueFiveDirection = fiveDirection,
            safeTakeoffAltitudeMeters = 20.0,
            takeoffSpeedMetersPerSecond = 3.0,
            obliqueForwardOverlapPercent = 70.0,
            obliqueSideOverlapPercent = 60.0,
        )
        return SurveyPlanner.plan(
            name = context?.getString(
                if (fiveDirection) R.string.debug_five_direction_regression_name
                else R.string.debug_nadir_regression_name,
            ) ?: if (fiveDirection) "DEBUG five-direction regression 50x40m"
            else "DEBUG nadir regression 50x40m",
            roi = roi,
            camera = CameraProfile.DJI_MINI_2,
            constraints = constraints,
            takeoffPoint = center,
        )
    }
}
