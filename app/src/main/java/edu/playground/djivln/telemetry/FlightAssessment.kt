package edu.playground.djivln.telemetry

data class FlightAssessment(
    val remainingFlightTimeSeconds: Int? = null,
    val timeNeededToGoHomeSeconds: Int? = null,
    val timeNeededToLandSeconds: Int? = null,
    val batteryNeededToGoHomePercent: Int? = null,
    val batteryNeededToLandPercent: Int? = null,
    val maxSafeFlightRadiusMeters: Double? = null,
) {
    companion object {
        fun sanitized(
            remainingFlightTimeSeconds: Int?,
            timeNeededToGoHomeSeconds: Int?,
            timeNeededToLandSeconds: Int?,
            batteryNeededToGoHomePercent: Int?,
            batteryNeededToLandPercent: Int?,
            maxSafeFlightRadiusMeters: Double?,
        ) = FlightAssessment(
            remainingFlightTimeSeconds = remainingFlightTimeSeconds.validSeconds(),
            timeNeededToGoHomeSeconds = timeNeededToGoHomeSeconds.validSeconds(),
            timeNeededToLandSeconds = timeNeededToLandSeconds.validSeconds(),
            batteryNeededToGoHomePercent = batteryNeededToGoHomePercent.validPercent(),
            batteryNeededToLandPercent = batteryNeededToLandPercent.validPercent(),
            maxSafeFlightRadiusMeters = maxSafeFlightRadiusMeters
                ?.takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000.0 },
        )

        private fun Int?.validSeconds(): Int? = this?.takeIf { it in 1 until 86_400 }
        private fun Int?.validPercent(): Int? = this?.takeIf { it in 0..100 }
    }
}

data class FlightWarningSnapshot(
    val code: String,
    val level: String,
    val title: String,
    val description: String?,
)
