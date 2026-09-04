package edu.playground.djivln.survey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** Pilot-style ASL legend used beside altitude-coloured terrain-follow routes. */
class TerrainAltitudeLegendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var minimumMeters = 0.0
    private var maximumMeters = 0.0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun showRange(minimumMeters: Double, maximumMeters: Double) {
        this.minimumMeters = minimumMeters
        this.maximumMeters = maximumMeters
        visibility = VISIBLE
        invalidate()
    }

    fun clearRange() {
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barLeft = 8f
        val barRight = 20f
        val top = 8f
        val bottom = height - 25f
        val steps = 96
        paint.style = Paint.Style.FILL
        for (step in 0 until steps) {
            val normalized = 1.0 - step.toDouble() / (steps - 1)
            paint.color = altitudeColor(normalized)
            val y0 = top + (bottom - top) * step / steps
            val y1 = top + (bottom - top) * (step + 1) / steps
            canvas.drawRect(barLeft, y0, barRight, y1 + 1f, paint)
        }
        paint.color = Color.WHITE
        paint.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        paint.textSize = 13f
        val ticks = 5
        for (tick in 0 until ticks) {
            val fraction = tick.toDouble() / (ticks - 1)
            val y = top + (bottom - top) * fraction
            val value = maximumMeters - (maximumMeters - minimumMeters) * fraction
            canvas.drawText(value.roundToInt().toString(), 25f, (y + 5f).toFloat(), paint)
        }
        paint.textSize = 12f
        canvas.drawText("ASL(m)", 6f, height - 7f, paint)
        paint.clearShadowLayer()
    }

    companion object {
        @JvmStatic
        fun altitudeColor(normalized: Double): Int {
            val t = normalized.coerceIn(0.0, 1.0).toFloat()
            // Pilot 2-like low-to-high sequence: magenta, blue, yellow, orange, red.
            val stops = intArrayOf(
                Color.rgb(210, 45, 220),
                Color.rgb(45, 70, 245),
                Color.rgb(255, 220, 20),
                Color.rgb(255, 125, 10),
                Color.rgb(235, 25, 20),
            )
            val scaled = t * (stops.size - 1)
            val lower = scaled.toInt().coerceAtMost(stops.size - 2)
            val local = scaled - lower
            return Color.rgb(
                (Color.red(stops[lower]) + (Color.red(stops[lower + 1]) - Color.red(stops[lower])) * local).roundToInt(),
                (Color.green(stops[lower]) + (Color.green(stops[lower + 1]) - Color.green(stops[lower])) * local).roundToInt(),
                (Color.blue(stops[lower]) + (Color.blue(stops[lower + 1]) - Color.blue(stops[lower])) * local).roundToInt(),
            )
        }
    }
}
