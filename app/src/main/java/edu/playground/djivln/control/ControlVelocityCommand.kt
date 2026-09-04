package edu.playground.djivln.control

data class ControlVelocityCommand(
    val vxMetersPerSecond: Double,
    val vyMetersPerSecond: Double,
    val vzMetersPerSecond: Double,
    val yawRateDegreesPerSecond: Double,
    val confidence: Double,
    val reason: String,
    val stopScore: Double? = null,
    val stopRequested: Boolean = false,
    /** Position target relative to the pose captured when this model action starts (body FRU). */
    val targetForwardMeters: Double? = null,
    val targetRightMeters: Double? = null,
    val targetUpMeters: Double? = null,
)
