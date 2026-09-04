package edu.playground.djivln.hil

import android.content.Context
import java.util.Locale

data class SimulatorOrigin(
    val latitude: Double,
    val longitude: Double,
) {
    fun label(): String = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
}

object SimulatorOriginParser {
    fun parse(
        latitudeText: String,
        longitudeText: String,
        context: Context? = null,
    ): Result<SimulatorOrigin> = runCatching {
        val latitude = latitudeText.trim().toDoubleOrNull()
            ?: error(context?.getString(edu.playground.djivln.R.string.latitude_format_invalid) ?: "Invalid latitude format")
        val longitude = longitudeText.trim().toDoubleOrNull()
            ?: error(context?.getString(edu.playground.djivln.R.string.longitude_format_invalid) ?: "Invalid longitude format")
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            context?.getString(edu.playground.djivln.R.string.latitude_range_invalid)
                ?: "Latitude must be between -90 and 90"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            context?.getString(edu.playground.djivln.R.string.longitude_range_invalid)
                ?: "Longitude must be between -180 and 180"
        }
        SimulatorOrigin(latitude, longitude)
    }
}

class SimulatorOriginStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): SimulatorOrigin {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull()
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull()
        return if (latitude != null && longitude != null) {
            SimulatorOriginParser.parse(latitude.toString(), longitude.toString(), appContext).getOrDefault(DEFAULT_ORIGIN)
        } else {
            DEFAULT_ORIGIN
        }
    }

    fun save(origin: SimulatorOrigin) {
        preferences.edit()
            .putString(KEY_LATITUDE, origin.latitude.toString())
            .putString(KEY_LONGITUDE, origin.longitude.toString())
            .apply()
    }

    companion object {
        val DEFAULT_ORIGIN = SimulatorOrigin(
            HilSimulatorLifecycleCoordinator.DEFAULT_LATITUDE,
            HilSimulatorLifecycleCoordinator.DEFAULT_LONGITUDE,
        )
        private const val PREFERENCES = "dji-simulator"
        private const val KEY_LATITUDE = "origin-latitude"
        private const val KEY_LONGITUDE = "origin-longitude"
    }
}
