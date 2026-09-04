package edu.playground.djivln.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.FrameLayout
import edu.playground.djivln.R
import edu.playground.djivln.databinding.ViewAircraftStatusBinding
import edu.playground.djivln.domain.telemetry.AircraftSnapshot

class AircraftStatusController(
    private val activity: Activity,
    container: FrameLayout,
    private val snapshot: () -> AircraftSnapshot,
    onQualification: () -> Unit,
) {
    private val binding = ViewAircraftStatusBinding.inflate(LayoutInflater.from(activity), container, false)

    init {
        container.addView(binding.root)
        binding.openQualification.setOnClickListener { onQualification() }
        render()
    }

    fun render() {
        val aircraft = snapshot()
        binding.statusSummary.text = if (aircraft.connected) {
            activity.getString(
                R.string.aircraft_status_connected,
                aircraft.flightMode ?: activity.getString(R.string.flight_mode_unknown),
                activity.getString(if (aircraft.isFlying) R.string.flight_state_flying else R.string.flight_state_ground_standby),
            )
        } else {
            activity.getString(R.string.aircraft_status_waiting_link)
        }
        binding.flightStatus.text = activity.getString(
            R.string.aircraft_flight_status_detail,
            aircraft.flightMode ?: "--",
            aircraft.relativeAltitudeMeters?.let { "%.1f m".format(it) } ?: "--",
            aircraft.headingDegrees?.let { "%.0f°".format(it) } ?: "--",
            aircraft.velocity?.let { "%.1f / %.1f / %.1f".format(it.north, it.east, it.up) } ?: "--",
        )
        binding.powerStatus.text = activity.getString(
            R.string.aircraft_power_status_detail,
            aircraft.aircraftBatteryPercent?.let { "$it%" } ?: "--",
            formatBatteryPair(aircraft.aircraftLeftBatteryPercent, aircraft.aircraftRightBatteryPercent),
            aircraft.remoteControllerBatteryPercent?.let { "$it%" } ?: "--",
            formatBatteryPairValue(aircraft.remoteControllerInternalBatteryPercent, aircraft.remoteControllerExternalBatteryPercent),
            aircraft.remoteControllerSignalPercent?.let { "$it%" } ?: "--",
            aircraft.gpsSatelliteCount ?: "--",
            aircraft.gpsSignalLevel ?: "--",
            activity.getString(if (aircraft.simulatorActive) R.string.state_enabled else R.string.state_disabled),
        )
        binding.sensorStatus.text = activity.getString(
            R.string.aircraft_sensor_status_detail,
            aircraft.altitudeAboveGroundMeters?.let { "%.1f m".format(it) } ?: "--",
            aircraft.altitudeAboveSeaLevelMeters?.let { "%.1f m".format(it) } ?: "--",
            aircraft.gimbalPitchDegrees?.let { "%.0f°".format(it) } ?: "--",
            formatLocation(aircraft.aircraftLocation),
            aircraft.remoteControllerLocation?.let(::formatLocation) ?: when (aircraft.remoteControllerGpsValid) {
                true -> activity.getString(R.string.rc_gps_valid_waiting_coordinates)
                false -> activity.getString(R.string.state_invalid)
                null -> activity.getString(R.string.state_no_data)
            },
        )
    }

    private fun formatLocation(location: edu.playground.djivln.domain.telemetry.GeoPoint?): String =
        location?.let { "%.6f, %.6f".format(it.latitude, it.longitude) } ?: "--"

    private fun formatBatteryPair(first: Int?, second: Int?): String =
        if (first == null && second == null) "" else
            activity.getString(
                R.string.aircraft_battery_pair,
                first?.let { "$it%" } ?: "--",
                second?.let { "$it%" } ?: "--",
            )

    private fun formatBatteryPairValue(first: Int?, second: Int?): String =
        "${first?.let { "$it%" } ?: "--"} / ${second?.let { "$it%" } ?: "--"}"
}
