package edu.playground.djivln.ui

import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.R
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.localization.UiText
import java.util.Locale

data class FlightUiState(
    val connected: Boolean = false,
    val productLabel: UiText = UiText.resource(R.string.status_aircraft_disconnected),
    val diagnostics: UiText = UiText.resource(R.string.status_waiting_telemetry),
)

data class SurveyUiState(val ready: Boolean = false)

data class VlnUiState(val ready: Boolean = false)

data class HilUiState(val ready: Boolean = false)

data class AppUiState(
    val sdkLabel: UiText = UiText.resource(R.string.sdk_starting),
    val error: String? = null,
    val flight: FlightUiState = FlightUiState(),
    val survey: SurveyUiState = SurveyUiState(),
    val vln: VlnUiState = VlnUiState(),
    val hil: HilUiState = HilUiState()
)

object AppUiStateReducer {
    fun fromSdk(
        state: DjiSdkBootstrap.State,
        telemetry: AircraftSnapshot = AircraftSnapshot()
    ): AppUiState {
        val sdkLabel = when {
            state.lastError != null -> UiText.resource(R.string.sdk_unavailable)
            state.connected -> UiText.resource(R.string.sdk_connected)
            state.registered -> UiText.resource(R.string.sdk_registered)
            state.initialized -> UiText.resource(R.string.sdk_initializing, state.initProgress)
            else -> UiText.resource(R.string.sdk_starting)
        }
        val productLabel = if (state.connected) {
            state.productId?.let { UiText.resource(R.string.aircraft_connected_product, it) }
                ?: UiText.resource(R.string.aircraft_connected)
        } else {
            UiText.resource(R.string.status_aircraft_disconnected)
        }
        return AppUiState(
            sdkLabel = sdkLabel,
            error = state.lastError,
            flight = FlightUiState(
                connected = state.connected,
                productLabel = productLabel,
                diagnostics = TelemetryDiagnosticsFormatter.format(telemetry)
            )
        )
    }
}

object TelemetryDiagnosticsFormatter {
    fun format(snapshot: AircraftSnapshot): UiText = UiText.resource(
        R.string.telemetry_diagnostics,
        UiText.resource(if (snapshot.connected) R.string.state_connected else R.string.state_disconnected),
        UiText.resource(if (snapshot.simulatorActive) R.string.state_yes else R.string.state_no),
        snapshot.aircraftLocation?.let { format("%.6f, %.6f", it.latitude, it.longitude) } ?: "N/A",
        meters(snapshot.relativeAltitudeMeters),
        meters(snapshot.altitudeAboveSeaLevelMeters),
        meters(snapshot.altitudeAboveGroundMeters),
        snapshot.attitude?.let { format("R %.1f°  P %.1f°  Y %.1f°", it.roll, it.pitch, it.yaw) } ?: "N/A",
        snapshot.velocity?.let { format("N %.2f  E %.2f  U %.2f m/s", it.north, it.east, it.up) } ?: "N/A",
        snapshot.headingDegrees?.let { format("%.1f°", it) } ?: "N/A",
        snapshot.gimbalPitchDegrees?.let { format("%.1f°", it) } ?: "N/A",
        percent(snapshot.aircraftBatteryPercent),
        percent(snapshot.remoteControllerBatteryPercent),
        snapshot.unsupportedFields.sorted().joinToString().takeIf(String::isNotEmpty)
            ?: UiText.resource(R.string.state_none),
    )

    private fun meters(value: Double?): String = value?.let { format("%.2f m", it) } ?: "N/A"
    private fun percent(value: Int?): String = value?.let { "$it%" } ?: "N/A"
    private fun format(pattern: String, vararg values: Any): String =
        String.format(Locale.US, pattern, *values)
}
