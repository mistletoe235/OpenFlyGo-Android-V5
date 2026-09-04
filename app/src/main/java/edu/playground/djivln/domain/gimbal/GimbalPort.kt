package edu.playground.djivln.domain.gimbal

data class GimbalState(
    val connected: Boolean = false,
    val pitchDegrees: Double? = null,
    val rollDegrees: Double? = null,
    val yawDegrees: Double? = null,
    val mode: String? = null,
    val pitchLimited: Boolean = false,
    val pitchMinimumDegrees: Double? = null,
    val pitchMaximumDegrees: Double? = null,
    val lastError: String? = null
)

fun interface GimbalStateListener {
    fun onStateChanged(state: GimbalState)
}

fun interface GimbalCompletion {
    fun complete(result: Result<Unit>)
}

interface GimbalPort {
    fun start(listener: GimbalStateListener)
    fun stop()
    fun state(): GimbalState
    fun rotateToPitch(pitchDegrees: Double, durationSeconds: Double = 1.0, completion: GimbalCompletion)
}
