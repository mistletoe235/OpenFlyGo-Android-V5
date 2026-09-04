package edu.playground.djivln.adapter.dji

import android.content.Context
import edu.playground.djivln.R
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import edu.playground.djivln.domain.gimbal.GimbalCompletion
import edu.playground.djivln.domain.gimbal.GimbalPort
import edu.playground.djivln.domain.gimbal.GimbalState
import edu.playground.djivln.domain.gimbal.GimbalStateListener

class DjiV5GimbalPort(
    private val index: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
    private val context: Context? = null,
) : GimbalPort {
    private val listenerOwner = Any()
    private var listener: GimbalStateListener? = null
    private var current = GimbalState()
    private var started = false

    override fun start(listener: GimbalStateListener) {
        this.listener = listener
        if (!started) {
            started = true
            installListeners()
            readSnapshot()
        }
        listener.onStateChanged(current)
    }

    override fun stop() {
        if (started) runCatching { KeyManager.getInstance().cancelListen(listenerOwner) }
        started = false
        listener = null
    }

    override fun state(): GimbalState = current

    override fun rotateToPitch(pitchDegrees: Double, durationSeconds: Double, completion: GimbalCompletion) {
        val request = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = pitchDegrees
            pitchIgnored = false
            rollIgnored = true
            yawIgnored = true
            duration = durationSeconds.coerceIn(0.1, 10.0)
            jointReferenceUsed = true
            timeout = 10
        }
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(GimbalKey.KeyRotateByAngle, index),
                request,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(value: EmptyMsg) = completion.complete(Result.success(Unit))
                    override fun onFailure(error: IDJIError) {
                        val exception = DjiOperationException(error)
                        update { it.copy(lastError = exception.message) }
                        completion.complete(Result.failure(exception))
                    }
                }
            )
        }.onFailure {
            update { state -> state.copy(lastError = it.message) }
            completion.complete(Result.failure(it))
        }
    }

    private fun installListeners() {
        safeListen(R.string.gimbal_listener_connection, "Connection") {
            KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyConnection, index), listenerOwner) { _, value ->
                update { it.copy(connected = value == true) }
            }
        }
        safeListen(R.string.gimbal_listener_attitude, "Attitude") {
            KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, index), listenerOwner) { _, value ->
                update { it.copy(pitchDegrees = value?.pitch, rollDegrees = value?.roll, yawDegrees = value?.yaw) }
            }
        }
        safeListen(R.string.gimbal_listener_mode, "Mode") {
            KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyGimbalMode, index), listenerOwner) { _, value ->
                update { it.copy(mode = value?.name) }
            }
        }
        safeListen(R.string.gimbal_listener_mechanical_limit, "Mechanical limit") {
            KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyLimitationState, index), listenerOwner) { _, value ->
                update { it.copy(pitchLimited = value?.pitch == true) }
            }
        }
        safeListen(R.string.gimbal_listener_angle_range, "Angle range") {
            KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, index), listenerOwner) { _, value ->
                update {
                    it.copy(
                        pitchMinimumDegrees = value?.pitch?.min,
                        pitchMaximumDegrees = value?.pitch?.max
                    )
                }
            }
        }
    }

    private fun readSnapshot() {
        runCatching {
            val manager = KeyManager.getInstance()
            val attitude = manager.getValue(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, index))
            val range = manager.getValue(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, index))
            current = current.copy(
                connected = manager.getValue(KeyTools.createKey(GimbalKey.KeyConnection, index)) == true,
                pitchDegrees = attitude?.pitch,
                rollDegrees = attitude?.roll,
                yawDegrees = attitude?.yaw,
                mode = manager.getValue(KeyTools.createKey(GimbalKey.KeyGimbalMode, index))?.name,
                pitchLimited = manager.getValue(KeyTools.createKey(GimbalKey.KeyLimitationState, index))?.pitch == true,
                pitchMinimumDegrees = range?.pitch?.min,
                pitchMaximumDegrees = range?.pitch?.max
            )
        }.onFailure { update { state -> state.copy(lastError = it.message) } }
    }

    private inline fun safeListen(resourceId: Int, fallbackLabel: String, block: () -> Unit) {
        val label = context?.getString(resourceId) ?: fallbackLabel
        runCatching(block).onFailure { error -> update { it.copy(lastError = "$label: ${error.message}") } }
    }

    private fun update(reducer: (GimbalState) -> GimbalState) {
        current = reducer(current)
        listener?.onStateChanged(current)
    }
}
