package edu.playground.djivln.survey

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.wayline.WaylinePhase

/** Strict fallback for aircrafts that fly a KMZ but omit mission execution callbacks. */
object DjiWaylineTelemetryPolicy {
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
