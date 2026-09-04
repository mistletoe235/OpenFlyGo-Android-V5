package edu.playground.djivln.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edu.playground.djivln.R
import edu.playground.djivln.adapter.dji.DjiV5FlightControlPort
import edu.playground.djivln.adapter.dji.DjiV5GimbalPort
import edu.playground.djivln.camera.CameraCaptureController
import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.flight.ControlOwner
import edu.playground.djivln.domain.flight.FlightControlPort
import edu.playground.djivln.domain.flight.FlightControlPortState
import edu.playground.djivln.domain.gimbal.GimbalPort
import edu.playground.djivln.domain.gimbal.GimbalState
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.qualification.DeviceQualificationPolicy
import edu.playground.djivln.qualification.HardwareGateLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceQualificationController(
    private val activity: Activity,
    private val aircraftSnapshot: () -> AircraftSnapshot,
    private val cameraSnapshot: () -> CameraCaptureController.Snapshot,
    private val flightControlPort: FlightControlPort = DjiV5FlightControlPort(),
    private val gimbalPort: GimbalPort = DjiV5GimbalPort(context = activity)
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var reportView: TextView? = null
    private var propsRemovedCheck: CheckBox? = null
    private var flightState = FlightControlPortState()
    private var gimbalState = GimbalState()
    private val events = ArrayDeque<String>()

    init {
        flightControlPort.start { state ->
            flightState = state
            activity.runOnUiThread(::render)
        }
        gimbalPort.start { state ->
            gimbalState = state
            activity.runOnUiThread(::render)
        }
    }

    fun show() {
        if (dialog?.isShowing == true) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        reportView = TextView(activity).apply {
            setTextColor(Color.rgb(225, 235, 247))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = panelBackground()
        }
        content.addView(reportView)
        propsRemovedCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.qualification_props_removed_confirm)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, _ -> render() }
        }
        content.addView(propsRemovedCheck)
        content.addView(section(activity.getString(R.string.qualification_control_section)))
        content.addView(row(
            actionButton(activity.getString(R.string.qualification_acquire_vs)) { acquire() },
            actionButton(activity.getString(R.string.qualification_release_zero)) { release() }
        ))
        content.addView(section(activity.getString(R.string.qualification_xyz_section)))
        content.addView(row(
            pulseButton(activity.getString(R.string.axis_x_forward), BodyVelocityCommand(forwardMetersPerSecond = 0.25)),
            pulseButton(activity.getString(R.string.axis_x_back), BodyVelocityCommand(forwardMetersPerSecond = -0.25))
        ))
        content.addView(row(
            pulseButton(activity.getString(R.string.axis_y_right), BodyVelocityCommand(rightMetersPerSecond = 0.25)),
            pulseButton(activity.getString(R.string.axis_y_left), BodyVelocityCommand(rightMetersPerSecond = -0.25))
        ))
        content.addView(row(
            pulseButton(activity.getString(R.string.axis_z_up), BodyVelocityCommand(upMetersPerSecond = 0.20)),
            pulseButton(activity.getString(R.string.axis_z_down), BodyVelocityCommand(upMetersPerSecond = -0.20))
        ))
        content.addView(section(activity.getString(R.string.qualification_gimbal_section)))
        content.addView(row(
            actionButton(activity.getString(R.string.gimbal_pitch_value, -45)) { rotateGimbal(-45.0) },
            actionButton(activity.getString(R.string.gimbal_pitch_value, -90)) { rotateGimbal(-90.0) }
        ))
        content.addView(actionButton(activity.getString(R.string.refresh_all_status)) {
            log(activity.getString(R.string.manual_refresh)); render()
        })

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.v5_device_qualification)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.action_close, null)
            .create()
            .also { created ->
                created.setOnDismissListener { dialog = null; stopPulse() }
                created.show()
                created.window?.decorView?.setBackgroundColor(Color.rgb(15, 22, 31))
            }
        render()
    }

    private fun acquire() {
        val snapshot = aircraftSnapshot()
        if (!snapshot.connected) return log(activity.getString(R.string.qualification_acquire_rejected))
        log(activity.getString(R.string.qualification_acquire_request))
        flightControlPort.acquire { result ->
            activity.runOnUiThread {
                log(result.fold(
                    { activity.getString(R.string.qualification_acquire_success) },
                    { activity.getString(R.string.qualification_acquire_failed, it.message) },
                ))
            }
        }
    }

    private fun release() {
        stopPulse()
        log(activity.getString(R.string.qualification_release_request))
        flightControlPort.release { result ->
            activity.runOnUiThread {
                log(result.fold(
                    { activity.getString(R.string.qualification_release_success) },
                    { activity.getString(R.string.qualification_release_failed, it.message) },
                ))
            }
        }
    }

    private fun sendPulse(command: BodyVelocityCommand) {
        val gate = DeviceQualificationPolicy.evaluate(
            aircraftSnapshot(),
            propsRemovedCheck?.isChecked == true
        )
        if (!gate.controlActionsAllowed) return log(activity.getString(
            R.string.qualification_xyz_blocked, qualificationReason(gate.level)))
        if (!flightState.enabled || flightState.owner != ControlOwner.APP) {
            return log(activity.getString(R.string.qualification_xyz_requires_vs))
        }
        stopPulse()
        flightControlPort.send(command)
            .onSuccess {
                log(activity.getString(R.string.qualification_xyz_pulse_sent, command))
                handler.postDelayed(::stopPulse, PULSE_MILLIS)
            }
            .onFailure { log(activity.getString(R.string.qualification_xyz_failed, it.message)) }
    }

    private fun stopPulse() {
        handler.removeCallbacksAndMessages(null)
        if (flightState.enabled && flightState.owner == ControlOwner.APP) {
            flightControlPort.send(BodyVelocityCommand.ZERO)
                .onFailure { log(activity.getString(R.string.qualification_zero_failed, it.message)) }
        }
    }

    private fun rotateGimbal(pitch: Double) {
        if (!aircraftSnapshot().connected) return log(activity.getString(R.string.qualification_gimbal_rejected))
        log(activity.getString(R.string.qualification_gimbal_request, pitch.toInt()))
        gimbalPort.rotateToPitch(pitch, 1.0) { result ->
            activity.runOnUiThread {
                log(result.fold(
                    { activity.getString(R.string.qualification_gimbal_accepted, pitch.toInt()) },
                    { activity.getString(R.string.qualification_gimbal_failed, it.message) },
                ))
            }
        }
    }

    private fun render() {
        val snapshot = aircraftSnapshot()
        val camera = cameraSnapshot()
        val gate = DeviceQualificationPolicy.evaluate(snapshot, propsRemovedCheck?.isChecked == true)
        reportView?.text = buildString {
            appendLine(activity.getString(R.string.qualification_report_level, qualificationLevel(gate.level)))
            appendLine(activity.getString(R.string.qualification_report_gate, qualificationReason(gate.level)))
            appendLine(activity.getString(R.string.qualification_report_aircraft,
                boolText(snapshot.connected), boolText(snapshot.motorsOn), boolText(snapshot.isFlying)))
            appendLine(activity.getString(R.string.qualification_report_simulator, boolText(snapshot.simulatorActive)))
            appendLine(activity.getString(R.string.qualification_report_vs,
                boolText(flightState.requested), boolText(flightState.enabled)))
            appendLine(activity.getString(R.string.qualification_report_owner,
                controlOwnerText(flightState.owner), boolText(flightState.advancedMode)))
            appendLine(activity.getString(R.string.qualification_report_control_error,
                flightState.lastError ?: activity.getString(R.string.state_none)))
            appendLine(activity.getString(R.string.qualification_report_gimbal,
                boolText(gimbalState.connected), gimbalState.pitchDegrees ?: activity.getString(R.string.state_no_data)))
            appendLine(activity.getString(R.string.qualification_report_gimbal_range,
                gimbalState.pitchMinimumDegrees ?: activity.getString(R.string.state_no_data),
                gimbalState.pitchMaximumDegrees ?: activity.getString(R.string.state_no_data)))
            appendLine(activity.getString(R.string.qualification_report_gimbal_limit,
                boolText(gimbalState.pitchLimited), gimbalState.lastError ?: activity.getString(R.string.state_none)))
            appendLine(activity.getString(R.string.qualification_report_camera,
                boolText(camera.connected), cameraModeText(camera.mode.name), boolText(camera.recording)))
            appendLine(activity.getString(R.string.qualification_report_media,
                storageStateText(camera.storageState),
                storageLocationText(camera.storageLocation.name),
                camera.supportedStorage.joinToString { storageLocationText(it.name) }
                    .ifBlank { activity.getString(R.string.state_none) }))
            appendLine(activity.getString(R.string.qualification_report_time, TIME_FORMAT.format(Date())))
            if (events.isNotEmpty()) {
                appendLine(activity.getString(R.string.qualification_recent_events))
                events.forEach(::appendLine)
            }
        }
    }

    private fun log(message: String) {
        events.addFirst("${TIME_FORMAT.format(Date())}  $message")
        while (events.size > 8) events.removeLast()
        render()
    }

    private fun pulseButton(label: String, command: BodyVelocityCommand): Button =
        actionButton(activity.getString(R.string.hold_action, label)) {}.apply {
            setOnClickListener { log(activity.getString(R.string.hold_required, label)) }
            setOnLongClickListener { sendPulse(command); true }
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    stopPulse()
                }
                false
            }
        }

    private fun actionButton(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun section(label: String) = TextView(activity).apply {
        text = label
        setTextColor(Color.rgb(129, 199, 255))
        textSize = 13f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun row(vararg views: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.rgb(23, 34, 47))
        setStroke(dp(1), Color.rgb(59, 82, 106))
    }

    private fun qualificationLevel(level: HardwareGateLevel): String = activity.getString(when (level) {
        HardwareGateLevel.OFFLINE -> R.string.qualification_level_offline
        HardwareGateLevel.CONNECTED -> R.string.qualification_level_connected
        HardwareGateLevel.SIMULATOR -> R.string.qualification_level_simulator
        HardwareGateLevel.PROPS_REMOVED -> R.string.qualification_level_props_removed
        HardwareGateLevel.AIRBORNE -> R.string.qualification_level_airborne
    })

    private fun qualificationReason(level: HardwareGateLevel): String = activity.getString(when (level) {
        HardwareGateLevel.OFFLINE -> R.string.qualification_reason_offline
        HardwareGateLevel.CONNECTED -> R.string.qualification_reason_connected
        HardwareGateLevel.SIMULATOR -> R.string.qualification_reason_simulator
        HardwareGateLevel.PROPS_REMOVED -> R.string.qualification_reason_props_removed
        HardwareGateLevel.AIRBORNE -> R.string.qualification_reason_airborne
    })

    private fun boolText(value: Boolean): String =
        activity.getString(if (value) R.string.state_yes else R.string.state_no)

    private fun controlOwnerText(owner: ControlOwner): String = activity.getString(when (owner) {
        ControlOwner.NONE -> R.string.control_owner_none
        ControlOwner.APP -> R.string.control_owner_app
        ControlOwner.REMOTE_CONTROLLER -> R.string.control_owner_rc
        ControlOwner.AUTOPILOT -> R.string.control_owner_autopilot
        ControlOwner.UNKNOWN -> R.string.state_no_data
    })

    private fun cameraModeText(name: String): String = activity.getString(when {
        "PHOTO" in name -> R.string.camera_mode_photo
        "VIDEO" in name || "RECORD" in name -> R.string.camera_mode_video
        else -> R.string.state_no_data
    })

    private fun storageLocationText(name: String): String = activity.getString(when {
        "SD" in name -> R.string.storage_sd_card
        "INTERNAL" in name -> R.string.storage_internal
        else -> R.string.storage_unknown
    })

    private fun storageStateText(name: String): String = activity.getString(when (name) {
        "NORMAL", "INSERTED" -> R.string.storage_state_normal
        "NOT_INSERTED" -> R.string.storage_state_not_inserted
        "UNKNOWN" -> R.string.state_no_data
        else -> R.string.storage_state_value
    }, name)

    override fun close() {
        stopPulse()
        dialog?.dismiss()
        flightControlPort.stop()
        gimbalPort.stop()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val PULSE_MILLIS = 350L
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
