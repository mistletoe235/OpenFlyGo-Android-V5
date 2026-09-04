package edu.playground.djivln.ui

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

/** Quality gate for phone fixes used only as the map's final location fallback. */
object MapLocationPolicy {
    const val MAX_AGE_MILLIS = 5L * 60L * 1_000L
    const val MAX_ACCURACY_METERS = 150f
    private const val MAX_FUTURE_OFFSET_MILLIS = 60_000L

    fun isUsable(
        latitude: Double,
        longitude: Double,
        timestampEpochMillis: Long,
        accuracyMeters: Float?,
        nowEpochMillis: Long,
    ): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return false
        if (latitude == 0.0 && longitude == 0.0) return false
        if (timestampEpochMillis <= 0L || nowEpochMillis <= 0L) return false
        val ageMillis = nowEpochMillis - timestampEpochMillis
        if (ageMillis > MAX_AGE_MILLIS || ageMillis < -MAX_FUTURE_OFFSET_MILLIS) return false
        if (accuracyMeters == null || !accuracyMeters.isFinite() ||
            accuracyMeters < 0f || accuracyMeters > MAX_ACCURACY_METERS
        ) return false
        return true
    }

    fun unavailableStatus(
        aircraftConnected: Boolean,
        aircraftSatellites: Int?,
        remoteControllerGpsValid: Boolean?,
        phoneLocationPermissionGranted: Boolean,
        phoneLocationListening: Boolean,
        compact: Boolean,
    ): UiText {
        if (compact) return UiText.resource(R.string.no_live_location)
        val aircraft = if (!aircraftConnected) {
            UiText.resource(R.string.status_aircraft_disconnected)
        } else {
            UiText.resource(R.string.map_aircraft_gps_satellites, aircraftSatellites ?: 0)
        }
        val remoteController = when (remoteControllerGpsValid) {
            true -> UiText.resource(R.string.map_rc_gps_no_coordinates)
            false -> UiText.resource(R.string.map_rc_gps_invalid)
            null -> UiText.resource(R.string.map_rc_gps_no_data)
        }
        val phone = when {
            !phoneLocationPermissionGranted -> UiText.resource(R.string.map_system_location_permission_missing)
            phoneLocationListening -> UiText.resource(R.string.map_system_location_no_coordinates)
            else -> UiText.resource(R.string.map_system_location_unavailable)
        }
        return UiText.resource(R.string.map_no_location_detail, aircraft, remoteController, phone)
    }
}
