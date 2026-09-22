package edu.playground.djivln.reconstruction

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edu.playground.djivln.R
import java.util.Locale
import kotlin.math.cos

object V86MissionPreviewDialog {
    fun show(activity: Activity, raw: String) {
        val preview = V86MissionPreview.decode(raw)
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(activity).apply {
                text = preview.name + "\n" + activity.getString(R.string.v86_remote_preview_only) +
                    "\n" + activity.getString(R.string.v86_remote_preview_stats, preview.points.size,
                        String.format(Locale.US, "%.1f", preview.points.minOf { it.altitudeMeters }),
                        String.format(Locale.US, "%.1f", preview.points.maxOf { it.altitudeMeters }))
            })
            addView(RouteView(activity, preview), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (260 * density).toInt()))
        }
        AlertDialog.Builder(activity).setTitle(R.string.v86_remote_mission)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private class RouteView(activity: Activity, preview: V86MissionPreview) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private val origin = preview.points.first()
        private val eastScale = cos(Math.toRadians(origin.latitude))
        private val points = preview.points.map { point ->
            (point.longitude - origin.longitude) * eastScale to (point.latitude - origin.latitude)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(20, 27, 38))
            val minimumEast = points.minOf { it.first }
            val maximumEast = points.maxOf { it.first }
            val minimumNorth = points.minOf { it.second }
            val maximumNorth = points.maxOf { it.second }
            val padding = 24 * density
            val scale = minOf((width - 2 * padding) / maxOf(maximumEast - minimumEast, 1e-8),
                (height - 2 * padding) / maxOf(maximumNorth - minimumNorth, 1e-8))
            val centerEast = (minimumEast + maximumEast) / 2
            val centerNorth = (minimumNorth + maximumNorth) / 2
            val screen = points.map { point ->
                (width / 2 + (point.first - centerEast) * scale).toFloat() to
                    (height / 2 - (point.second - centerNorth) * scale).toFloat()
            }
            val path = Path()
            screen.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
            }
            paint.color = Color.rgb(83, 182, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * density
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            canvas.drawCircle(screen.first().first, screen.first().second, 4 * density, paint)
            paint.color = Color.rgb(255, 175, 85)
            canvas.drawCircle(screen.last().first, screen.last().second, 4 * density, paint)
            paint.color = Color.WHITE
            paint.textSize = 12 * density
            canvas.drawText("N ↑", 8 * density, 17 * density, paint)
        }
    }
}
