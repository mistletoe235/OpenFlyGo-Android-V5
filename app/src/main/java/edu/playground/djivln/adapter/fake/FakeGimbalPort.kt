package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.gimbal.GimbalCompletion
import edu.playground.djivln.domain.gimbal.GimbalPort
import edu.playground.djivln.domain.gimbal.GimbalState
import edu.playground.djivln.domain.gimbal.GimbalStateListener

class FakeGimbalPort(
    minimumPitchDegrees: Double = -90.0,
    maximumPitchDegrees: Double = 30.0
) : GimbalPort {
    private var listener: GimbalStateListener? = null
    private var current = GimbalState(
        connected = true,
        pitchDegrees = 0.0,
        pitchMinimumDegrees = minimumPitchDegrees,
        pitchMaximumDegrees = maximumPitchDegrees
    )

    override fun start(listener: GimbalStateListener) {
        this.listener = listener
        listener.onStateChanged(current)
    }

    override fun stop() {
        listener = null
    }

    override fun state(): GimbalState = current

    override fun rotateToPitch(pitchDegrees: Double, durationSeconds: Double, completion: GimbalCompletion) {
        val minimum = current.pitchMinimumDegrees ?: -90.0
        val maximum = current.pitchMaximumDegrees ?: 30.0
        val actual = pitchDegrees.coerceIn(minimum, maximum)
        current = current.copy(pitchDegrees = actual, pitchLimited = actual != pitchDegrees)
        listener?.onStateChanged(current)
        completion.complete(Result.success(Unit))
    }

    fun setMechanicalMinimum(minimumPitchDegrees: Double) {
        current = current.copy(pitchMinimumDegrees = minimumPitchDegrees)
        listener?.onStateChanged(current)
    }
}
