package edu.playground.djivln.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import edu.playground.djivln.R
import edu.playground.djivln.domain.telemetry.AircraftSnapshot

internal data class FlightEnduranceBarState(
    val connected: Boolean,
    val remainingChargePercent: Int,
    val batteryNeededToGoHomePercent: Int?,
    val batteryNeededToLandPercent: Int?,
    val remainingFlightTimeSeconds: Int?,
) {
    val timeLabel: String
        get() = FlightEnduranceBarPolicy.formatTime(remainingFlightTimeSeconds)
}

internal object FlightEnduranceBarPolicy {
    fun from(snapshot: AircraftSnapshot): FlightEnduranceBarState = FlightEnduranceBarState(
        connected = snapshot.connected,
        remainingChargePercent = snapshot.aircraftBatteryPercent.validPercent() ?: 0,
        batteryNeededToGoHomePercent = snapshot.batteryNeededToGoHomePercent.validPercent(),
        batteryNeededToLandPercent = snapshot.batteryNeededToLandPercent.validPercent(),
        remainingFlightTimeSeconds = snapshot.remainingFlightTimeSeconds?.takeIf { it >= 0 },
    )

    fun formatTime(seconds: Int?): String {
        val value = seconds?.takeIf { it > 0 } ?: return "--:--"
        val hours = value / 3_600
        val minutes = value % 3_600 / 60
        val remainingSeconds = value % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
        } else {
            "%02d:%02d".format(minutes, remainingSeconds)
        }
    }

    private fun Int?.validPercent(): Int? = this?.takeIf { it in 0..100 }
}

/** DJI-style endurance scale driven by the app's normalized, cross-aircraft telemetry. */
class FlightEnduranceBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeWidth = dp(5f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 31, 36)
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var state = FlightEnduranceBarState(false, 0, null, null, null)

    fun render(snapshot: AircraftSnapshot) {
        val next = FlightEnduranceBarPolicy.from(snapshot)
        if (next == state && visibility == if (next.connected) VISIBLE else GONE) return
        state = next
        visibility = if (next.connected) VISIBLE else GONE
        contentDescription = buildString {
            append(context.getString(R.string.endurance_remaining_time, next.timeLabel))
            append(context.getString(R.string.endurance_battery, next.remainingChargePercent))
            next.batteryNeededToGoHomePercent?.let { append(context.getString(R.string.endurance_rth_needed, it)) }
            next.batteryNeededToLandPercent?.let { append(context.getString(R.string.endurance_landing_needed, it)) }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!state.connected || width <= 0 || height <= 0) return
        val left = dp(4f)
        val right = width - dp(4f)
        val centerY = height / 2f
        val usableWidth = (right - left).coerceAtLeast(1f)
        fun x(percent: Int): Float = left + usableWidth * percent.coerceIn(0, 100) / 100f

        barPaint.color = Color.argb(145, 208, 216, 224)
        canvas.drawLine(left, centerY, right, centerY, barPaint)

        val charge = state.remainingChargePercent
        val land = state.batteryNeededToLandPercent?.coerceAtMost(charge) ?: 0
        val home = state.batteryNeededToGoHomePercent?.coerceIn(land, charge) ?: land
        drawSegment(canvas, left, x(land), centerY, Color.rgb(244, 67, 54))
        drawSegment(canvas, x(land), x(home), centerY, Color.rgb(255, 193, 7))
        drawSegment(canvas, x(home), x(charge), centerY, Color.rgb(42, 201, 108))

        state.batteryNeededToGoHomePercent?.let { percent ->
            val markerX = x(percent)
            markerPaint.color = Color.WHITE
            canvas.drawCircle(markerX, centerY, dp(8f), markerPaint)
            markerPaint.color = Color.rgb(38, 43, 48)
            markerPaint.textSize = dp(9f)
            canvas.drawText("H", markerX, centerY + dp(3.2f), markerPaint)
        }

        val label = state.timeLabel
        val pillWidth = (textPaint.measureText(label) + dp(16f)).coerceAtLeast(dp(52f))
        val pillCenter = x(charge).coerceIn(left + pillWidth / 2f, right - pillWidth / 2f)
        val pill = RectF(
            pillCenter - pillWidth / 2f,
            centerY - dp(10f),
            pillCenter + pillWidth / 2f,
            centerY + dp(10f),
        )
        markerPaint.color = Color.WHITE
        canvas.drawRoundRect(pill, dp(10f), dp(10f), markerPaint)
        canvas.drawText(label, pillCenter, centerY + dp(3.8f), textPaint)
    }

    private fun drawSegment(canvas: Canvas, start: Float, end: Float, y: Float, color: Int) {
        if (end <= start) return
        barPaint.color = color
        canvas.drawLine(start, y, end, y, barPaint)
    }

    private fun dp(value: Float): Float = value * density
}
