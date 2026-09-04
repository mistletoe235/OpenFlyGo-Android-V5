package edu.playground.djivln.control

/**
 * Converts our body-FRU command into DJI MSDK's BODY/VELOCITY control fields.
 *
 * DJI names the body-X velocity field `roll` and the body-Y velocity field
 * `pitch`: body X is forward, body Y is right, and vertical velocity is up.
 */
object BodyVelocityToDjiAxes {
    data class Axes(
        val pitch: Double,
        val roll: Double,
        val verticalThrottle: Double,
        val yaw: Double,
    )

    fun map(
        forward: Double,
        right: Double,
        up: Double,
        yawRateDegreesPerSecond: Double,
    ): Axes = Axes(
        pitch = right,
        roll = forward,
        verticalThrottle = up,
        yaw = yawRateDegreesPerSecond,
    )
}
