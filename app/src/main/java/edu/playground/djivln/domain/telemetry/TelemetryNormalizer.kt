package edu.playground.djivln.domain.telemetry

object TelemetryNormalizer {
    fun geoPoint(latitude: Double?, longitude: Double?, altitudeMeters: Double? = null): GeoPoint? {
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return GeoPoint(latitude, longitude, altitudeMeters?.takeIf(Double::isFinite))
    }

    fun attitude(roll: Double?, pitch: Double?, yaw: Double?): AttitudeDegrees? {
        if (roll == null || pitch == null || yaw == null) return null
        if (!roll.isFinite() || !pitch.isFinite() || !yaw.isFinite()) return null
        return AttitudeDegrees(roll, pitch, yaw)
    }

    fun nedVelocity(north: Double?, east: Double?, down: Double?): VelocityMetersPerSecond? {
        if (north == null || east == null || down == null) return null
        if (!north.isFinite() || !east.isFinite() || !down.isFinite()) return null
        return VelocityMetersPerSecond(north = north, east = east, up = -down)
    }
}
