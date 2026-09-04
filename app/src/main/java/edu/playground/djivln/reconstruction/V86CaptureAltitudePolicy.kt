package edu.playground.djivln.reconstruction

internal object V86CaptureAltitudePolicy {
    data class Resolution(
        val absoluteAltitudeMeters: Double,
        val source: String,
    )

    fun resolve(
        frameAbsoluteAltitudeMeters: Double?,
        relativeAltitudeMeters: Double?,
        takeoffAbsoluteAltitudeMeters: Double?,
        frameAbsoluteAltitudeSource: String? = null,
    ): Resolution? {
        frameAbsoluteAltitudeMeters
            ?.takeIf(Double::isFinite)
            ?.let { return Resolution(it, frameAbsoluteAltitudeSource ?: "aircraft_asl") }
        val relative = relativeAltitudeMeters?.takeIf(Double::isFinite) ?: return null
        val takeoff = takeoffAbsoluteAltitudeMeters?.takeIf(Double::isFinite) ?: return null
        return Resolution(takeoff + relative, "takeoff_asl_plus_relative")
    }
}
