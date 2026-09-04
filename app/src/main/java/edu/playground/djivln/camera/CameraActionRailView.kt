package edu.playground.djivln.camera

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import edu.playground.djivln.R

class CameraActionRailView(
    context: Context,
    private val controller: CameraCaptureController,
    openGallery: () -> Unit,
    cycleLens: (() -> Unit)? = null,
) : LinearLayout(context) {
    private var latestSnapshot = CameraCaptureController.Snapshot()
    private var lensSwitchAvailable = false
    private val lensButton = railButton(context.getString(R.string.lens_value, "--")) { cycleLens?.invoke() }
    private val shutterButton = iconButton(context.getString(R.string.action_take_photo)) { controller.takePhoto() }.apply {
        background = shutterBackground()
    }
    private val recordButton = iconButton(context.getString(R.string.start_recording)) { controller.toggleRecording() }.apply {
        background = roundedBackground(0xE60A5D8F.toInt(), 0xFF2997FF.toInt(), 9)
    }
    private val statusView = TextView(context).apply {
        text = "00:00"
        textSize = 9f
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setTextColor(0xFFFFA69C.toInt())
    }
    private val messageView = TextView(context).apply {
        text = context.getString(R.string.waiting_camera)
        textSize = 7f
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(0xFFB9C3CE.toInt())
    }
    private val galleryButton = iconButton(context.getString(R.string.open_gallery), openGallery).apply {
        background = roundedBackground(0xB30A0E13.toInt(), 0x38FFFFFF, 8)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, 0)
        // Consume touches in the rail's padding/gaps when it overlays a full-screen
        // map; only the child buttons should trigger actions.
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = null
        setButtonIcon(lensButton, R.drawable.ic_hud_camera)
        setButtonIcon(shutterButton, R.drawable.ic_hud_camera, 0xFF30343A.toInt())
        setButtonIcon(recordButton, R.drawable.ic_hud_video)
        setButtonIcon(galleryButton, R.drawable.ic_hud_photo)
        lensButton.visibility = View.GONE
        lensButton.background = null
        lensButton.elevation = 0f
        addView(lensButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(20)))
        addView(shutterButton, LayoutParams(dp(50), dp(50)).apply { topMargin = dp(9); gravity = Gravity.CENTER_HORIZONTAL })
        addView(recordButton, LayoutParams(dp(38), dp(34)).apply { topMargin = dp(9); gravity = Gravity.CENTER_HORIZONTAL })
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(2) })
        addView(messageView, LayoutParams(LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(1) })
        addView(galleryButton, LayoutParams(dp(36), dp(34)).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL })
    }

    fun render(snapshot: CameraCaptureController.Snapshot) {
        latestSnapshot = snapshot
        val videoSelected = snapshot.mode.isVideoMode
        recordButton.contentDescription = context.getString(
            if (snapshot.recording) R.string.stop_recording else R.string.start_recording,
        )
        setButtonIcon(recordButton, if (snapshot.recording) R.drawable.ic_hud_stop else R.drawable.ic_hud_video)
        recordButton.background = roundedBackground(
            if (snapshot.recording) 0xE6A5202B.toInt() else 0xE60A5D8F.toInt(),
            if (snapshot.recording) 0xFFFF453A.toInt() else 0xFF2997FF.toInt(),
            9,
        )
        shutterButton.isEnabled = snapshot.connected && !snapshot.busy && !snapshot.recording
        recordButton.isEnabled = snapshot.connected && !snapshot.busy
        galleryButton.isEnabled = snapshot.connected && !snapshot.busy && !snapshot.recording
        updateLensAvailability()
        listOf<View>(lensButton, shutterButton, recordButton, galleryButton).forEach { button ->
            button.alpha = if (button.isEnabled) 1f else 0.35f
        }
        statusView.text = "%02d:%02d".format(snapshot.recordingSeconds / 60, snapshot.recordingSeconds % 60)
        statusView.visibility = if (videoSelected || snapshot.recording) View.VISIBLE else View.GONE
        val reportsProblem = snapshot.messageSeverity == CameraCaptureController.MessageSeverity.ERROR
        messageView.text = snapshot.message.ifBlank { context.getString(R.string.waiting_camera) }
        messageView.setTextColor(if (reportsProblem) 0xFFFF8A80.toInt() else 0xFFB9C3CE.toInt())
        messageView.visibility = if (reportsProblem) View.VISIBLE else View.GONE
    }

    fun renderLens(sourceName: String?, availableSourceCount: Int) {
        lensButton.text = context.getString(R.string.lens_value, sourceName.toLensLabel())
        lensSwitchAvailable = availableSourceCount > 1
        lensButton.visibility = if (lensSwitchAvailable) View.VISIBLE else View.GONE
        updateLensAvailability()
        lensButton.alpha = if (lensButton.isEnabled) 1f else 0.35f
    }

    private fun updateLensAvailability() {
        lensButton.isEnabled = lensSwitchAvailable && latestSnapshot.connected &&
            !latestSnapshot.busy && !latestSnapshot.recording
    }

    private fun String?.toLensLabel(): String = when (this?.uppercase()) {
        "WIDE_CAMERA" -> context.getString(R.string.lens_wide)
        "ZOOM_CAMERA" -> context.getString(R.string.lens_zoom)
        "INFRARED_CAMERA" -> context.getString(R.string.lens_infrared)
        "DEFAULT_CAMERA" -> context.getString(R.string.lens_default)
        null, "", "UNKNOWN" -> "--"
        else -> replace("_CAMERA", "").replace('_', ' ').take(8)
    }

    private fun railButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 8f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(3), 0, dp(3), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(0xD90A0E13.toInt())
            setStroke(dp(1), 0x38FFFFFF)
        }
        elevation = dp(2).toFloat()
        isHapticFeedbackEnabled = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pulseButtonVibration(view)
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(45L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(70L).start()
                }
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun iconButton(description: String, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isHapticFeedbackEnabled = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pulseButtonVibration(view)
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(45L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(70L).start()
                }
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun circleBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun shutterBackground() = LayerDrawable(
        arrayOf(
            circleBackground(0x00000000, 0xCCFFFFFF.toInt()),
            circleBackground(0xFFF2F3F5.toInt(), 0xFFB8C0C8.toInt()),
        ),
    ).apply {
        setLayerInset(1, dp(4), dp(4), dp(4), dp(4))
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun setButtonIcon(button: Button, drawableRes: Int, tint: Int = 0xFFFFFFFF.toInt()) {
        val icon = context.getDrawable(drawableRes)?.mutate()?.apply { setTint(tint) }
        button.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        button.compoundDrawablePadding = if (button.text.isNullOrBlank()) 0 else dp(3)
        button.gravity = Gravity.CENTER
    }

    private fun setButtonIcon(button: ImageButton, drawableRes: Int, tint: Int = 0xFFFFFFFF.toInt()) {
        val icon = context.getDrawable(drawableRes)?.mutate()?.apply { setTint(tint) }
        button.setImageDrawable(icon)
        button.scaleType = ImageView.ScaleType.CENTER
    }

    private fun pulseButtonVibration(view: android.view.View) {
        val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() != true) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(20L, 80))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20L)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
