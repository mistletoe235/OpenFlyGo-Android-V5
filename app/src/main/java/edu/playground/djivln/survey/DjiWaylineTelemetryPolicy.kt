package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.wayline.WaylinePhase

/** Strict fallback for aircrafts that fly a KMZ but omit mission execution callbacks. */
object DjiWaylineTelemetryPolicy {
    fun confirmsInterruption(
        phase: WaylinePhase,
        aircraft: AircraftSnapshot,
        nowNanos: Long,
        pauseRejected: Boolean = false,
    ): Boolean {
        if (phase !in setOf(WaylinePhase.PREPARING, WaylinePhase.RECOVERING, WaylinePhase.EXECUTING)) return false
        val received = aircraft.flightStateUpdatedAtNanos
        if (!aircraft.connected || received <= 0 || nowNanos < received || nowNanos - received > 2_000_000_000L) return false
        val mode = aircraft.flightMode.orEmpty().trim().uppercase()
        val goHome = aircraft.goHomeState.orEmpty().trim().uppercase()
        if (mode.contains("GOHOME") || mode.contains("GO_HOME") || mode.contains("LANDING") ||
            goHome in setOf("RETURNING_TO_HOME", "LANDING")
        ) return true
        return pauseRejected && phase == WaylinePhase.EXECUTING &&
            mode in setOf("P-GPS", "GPS_NORMAL", "OPTI", "ATTI", "GPS_SPORT", "GPS_TRIPOD")
    }

    fun confirmsExecution(
        captureArmed: Boolean,
        phase: WaylinePhase,
        aircraft: AircraftSnapshot,
    ): Boolean {
        if (!captureArmed || phase !in setOf(WaylinePhase.PREPARING, WaylinePhase.RECOVERING)) return false
        if (!aircraft.connected || !aircraft.isFlying) return false
        return isWaypointFlightMode(aircraft.flightMode)
    }

    fun isWaypointFlightMode(raw: String?): Boolean = when (raw.orEmpty().trim().uppercase()) {
        "F-WP", "WAYPOINT", "WAYPOINT_MISSION" -> true
        else -> false
    }
}
