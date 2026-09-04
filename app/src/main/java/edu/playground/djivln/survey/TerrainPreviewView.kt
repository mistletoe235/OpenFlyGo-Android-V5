package edu.playground.djivln.survey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import edu.playground.djivln.R
import kotlin.math.hypot

/** Compact, map-oriented DSM alignment preview; it never controls flight. */
class TerrainPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var info: TerrainRasterInfo? = null
    private var grid: DoubleArray? = null
    private var columns = 0
    private var rows = 0
    private var mission: SurveyMission? = null
    private var editableRoi: List<GeoPoint> = emptyList()
    private var roiListener: ((List<GeoPoint>) -> Unit)? = null
    private var draggingIndex = -1
    private var mapOverlayMode = false
    private var terrainLabel = "DSM"

    fun setMapOverlayMode(enabled: Boolean) {
        mapOverlayMode = enabled
        setBackgroundColor(Color.TRANSPARENT)
        invalidate()
    }

    fun showTerrain(
        info: TerrainRasterInfo,
        grid: DoubleArray,
        columns: Int,
        rows: Int,
        label: String = "DSM",
    ) {
        require(grid.size == columns * rows)
        this.info = info
        this.grid = grid
        this.columns = columns
        this.rows = rows
        terrainLabel = label
        invalidate()
    }

    fun showMission(mission: SurveyMission?) {
        this.mission = mission
        editableRoi = mission?.roi ?: emptyList()
        if (mission == null) roiListener = null
        invalidate()
    }

    fun editRoi(roi: List<GeoPoint>, listener: (List<GeoPoint>) -> Unit) {
        require(roi.size >= 3)
        editableRoi = roi
        roiListener = listener
        invalidate()
    }

    fun clearTerrain() {
        info = null
        grid = null
        columns = 0
        rows = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!mapOverlayMode) canvas.drawColor(Color.rgb(25, 29, 34))
        val values = grid
        val mapBottom = height.toFloat()
        val valid = values?.filter { it.isFinite() }.orEmpty()
        // A raster stretched to this View is not geographically registered to the
        // live map camera. Keep the map overlay transparent until a projection-bound
        // ground overlay is available; V4 visualizes terrain on the route/legend only.
        if (!mapOverlayMode && values != null && valid.isNotEmpty() && columns > 0 && rows > 0) {
            val min = valid.min()
            val max = valid.max()
            val cellW = width.toFloat() / columns
            val cellH = mapBottom / rows
            paint.style = Paint.Style.FILL
            for (row in 0 until rows) for (column in 0 until columns) {
                val value = values[row * columns + column]
                paint.color = if (!value.isFinite()) Color.argb(if (mapOverlayMode) 70 else 255, 70, 30, 35) else terrainColor(
                    if (max <= min) 0.5 else (value - min) / (max - min),
                ).let { color -> if (mapOverlayMode) Color.argb(105, Color.red(color), Color.green(color), Color.blue(color)) else color }
                canvas.drawRect(column * cellW, row * cellH,
                    (column + 1) * cellW + 1, (row + 1) * cellH + 1, paint)
            }
            drawLegend(canvas, min, max)
            drawTerrainLabel(canvas, min, max)
        } else if (!mapOverlayMode) {
            drawEmpty(canvas)
        }
        if (!mapOverlayMode) drawMapOverlays(canvas, mapBottom)
    }

    private fun drawMapOverlays(canvas: Canvas, mapBottom: Float) {
        val bounds = visibleBounds() ?: return
        val current = mission
        fun x(longitude: Double) = ((longitude - bounds.minimumLongitude) /
            (bounds.maximumLongitude - bounds.minimumLongitude) * width).toFloat()
        fun y(latitude: Double) = ((bounds.maximumLatitude - latitude) /
            (bounds.maximumLatitude - bounds.minimumLatitude) * mapBottom).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.WHITE
        val roi = Path()
        editableRoi.forEachIndexed { index, point ->
            if (index == 0) roi.moveTo(x(point.longitude), y(point.latitude))
            else roi.lineTo(x(point.longitude), y(point.latitude))
        }
        roi.close()
        canvas.drawPath(roi, paint)
        paint.style = Paint.Style.FILL
        editableRoi.forEachIndexed { index, point ->
            paint.color = if (index == draggingIndex) Color.YELLOW else Color.WHITE
            canvas.drawCircle(x(point.longitude), y(point.latitude), 11f, paint)
        }
        if (current == null) return
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        paint.color = Color.CYAN
        current.surveyPasses().forEach { pass ->
            val path = Path()
            pass.waypoints.forEachIndexed { index, waypoint ->
                val px = x(waypoint.point.longitude)
                val py = y(waypoint.point.latitude)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // In the survey page this view is a transparent DSM colour overlay above
        // the live map. The native map owns all gestures and vertex editing there.
        if (mapOverlayMode) return false
        if (editableRoi.size < 3) return false
        val bounds = visibleBounds() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingIndex = editableRoi.indices.minByOrNull { index ->
                    val point = editableRoi[index]
                    hypot(
                        event.x - longitudeToX(point.longitude, bounds),
                        event.y - latitudeToY(point.latitude, bounds),
                    )
                } ?: -1
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex !in editableRoi.indices) return false
                val changed = editableRoi.toMutableList()
                changed[draggingIndex] = GeoPoint(
                    latitude = yToLatitude(event.y, bounds),
                    longitude = xToLongitude(event.x, bounds),
                    altitudeMeters = changed[draggingIndex].altitudeMeters,
                )
                editableRoi = changed
                mission = mission?.copy(roi = changed)
                roiListener?.invoke(changed)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun visibleBounds(): TerrainRasterInfo? {
        info?.let { return it }
        val points = editableRoi.ifEmpty { mission?.roi.orEmpty() }
        if (points.size < 3) return null
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latMargin = maxOf((maxLat - minLat) * 0.2, 0.0001)
        val lonMargin = maxOf((maxLon - minLon) * 0.2, 0.0001)
        return TerrainRasterInfo(
            displayName = context.getString(R.string.survey_area),
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            epsg = 4326,
            noDataValue = null,
            pixelSizeX = 1.0,
            pixelSizeY = 1.0,
            minimumLatitude = minLat - latMargin,
            maximumLatitude = maxLat + latMargin,
            minimumLongitude = minLon - lonMargin,
            maximumLongitude = maxLon + lonMargin,
        )
    }

    private fun longitudeToX(longitude: Double, bounds: TerrainRasterInfo) =
        ((longitude - bounds.minimumLongitude) /
            (bounds.maximumLongitude - bounds.minimumLongitude) * width).toFloat()

    private fun latitudeToY(latitude: Double, bounds: TerrainRasterInfo) =
        ((bounds.maximumLatitude - latitude) /
            (bounds.maximumLatitude - bounds.minimumLatitude) * height).toFloat()

    private fun xToLongitude(x: Float, bounds: TerrainRasterInfo) = bounds.minimumLongitude +
        (x / width.coerceAtLeast(1)).coerceIn(0f, 1f) *
        (bounds.maximumLongitude - bounds.minimumLongitude)

    private fun yToLatitude(y: Float, bounds: TerrainRasterInfo) = bounds.maximumLatitude -
        (y / height.coerceAtLeast(1)).coerceIn(0f, 1f) *
        (bounds.maximumLatitude - bounds.minimumLatitude)

    private fun drawTerrainLabel(canvas: Canvas, min: Double, max: Double) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 15, 18, 22)
        canvas.drawRoundRect(5f, 3f, 245f, 33f, 6f, 6f, paint)
        paint.color = Color.WHITE
        paint.textSize = 22f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("$terrainLabel ${"%.1f".format(min)}–${"%.1f".format(max)} m", 10f, 25f, paint)
    }

    private fun drawLegend(canvas: Canvas, min: Double, max: Double) {
        paint.style = Paint.Style.FILL
        val left = 10f
        val right = width - 10f
        val top = height - 27f
        val bottom = height - 9f
        paint.color = Color.argb(190, 15, 18, 22)
        canvas.drawRoundRect(left - 5f, top - 16f, right + 5f, bottom + 5f, 6f, 6f, paint)
        val steps = 48
        for (step in 0 until steps) {
            paint.color = terrainColor(step.toDouble() / (steps - 1))
            val x0 = left + (right - left) * step / steps
            val x1 = left + (right - left) * (step + 1) / steps
            canvas.drawRect(x0, top, x1 + 1f, bottom, paint)
        }
        paint.color = Color.WHITE
        paint.textSize = 16f
        canvas.drawText(context.getString(R.string.terrain_low_meters, min), left, top - 2f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.terrain_high_meters, max), right, top - 2f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.color = Color.LTGRAY
        paint.textSize = 22f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.terrain_preview_empty_instruction), 16f, height / 2f, paint)
    }

    private fun terrainColor(value: Double): Int {
        val t = value.coerceIn(0.0, 1.0).toFloat()
        return Color.HSVToColor(floatArrayOf(220f - 220f * t, 0.75f, 0.95f))
    }
}
