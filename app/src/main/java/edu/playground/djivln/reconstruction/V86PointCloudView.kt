package edu.playground.djivln.reconstruction

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.View
import edu.playground.djivln.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Lightweight native reviewer for the standard binary XYZ+RGB PLY returned by V86. */
class V86PointCloudView(context: Context) : View(context) {
    enum class ViewPreset { ISOMETRIC, TOP, FRONT }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.3f, 8f)
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onDoubleTap(event: MotionEvent): Boolean {
            resetView()
            return true
        }
    })
    private var cloud: V86PointCloud? = null
    private var centerX = 0f
    private var centerY = 0f
    private var centerZ = 0f
    private var spanX = 1f
    private var spanY = 1f
    private var spanZ = 1f
    private var yaw = -0.35f
    private var pitch = 1.10f
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var showCandidates = true

    fun setPointCloud(value: V86PointCloud) {
        cloud = value
        calculateBounds(value)
        resetView()
    }

    fun resetView() {
        setPreset(ViewPreset.ISOMETRIC)
    }

    fun setPreset(preset: ViewPreset) {
        when (preset) {
            ViewPreset.ISOMETRIC -> {
                yaw = -0.35f
                pitch = 1.10f
            }
            ViewPreset.TOP -> {
                yaw = 0f
                // py = cos(pitch) * north - sin(pitch) * up: top view is XY.
                pitch = 0f
            }
            ViewPreset.FRONT -> {
                yaw = 0f
                pitch = (Math.PI / 2).toFloat()
            }
        }
        zoom = 1f
        invalidate()
    }

    fun setCandidatesVisible(visible: Boolean) {
        showCandidates = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 13, 18))
        val current = cloud
        if (current == null || current.size == 0) {
            canvas.drawText(context.getString(R.string.point_cloud_waiting), 24f, 44f, textPaint)
            return
        }
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        // Fit the projected robust bounds independently to the wide viewport.
        // A bounding sphere wastes most of a landscape phone display for a long,
        // low survey scene and makes the useful cloud look like a small island.
        val projectedWidth = (abs(cy) * spanX + abs(sy) * spanY).coerceAtLeast(1f)
        val rotatedYSpan = abs(sy) * spanX + abs(cy) * spanY
        val projectedHeight = (abs(cp) * rotatedYSpan + abs(sp) * spanZ).coerceAtLeast(1f)
        val scale = minOf(
            width * 0.90f / projectedWidth,
            height * 0.78f / projectedHeight,
        ) * zoom
        for (index in 0 until current.size) {
            val x = current.xyz[index * 3] - centerX
            val y = current.xyz[index * 3 + 1] - centerY
            val z = current.xyz[index * 3 + 2] - centerZ
            val rx = cy * x - sy * y
            val ry = sy * x + cy * y
            val py = cp * ry - sp * z
            val sx = width * 0.5f + rx * scale
            val screenY = height * 0.53f - py * scale
            if (sx < 0f || sx >= width || screenY < 0f || screenY >= height) continue
            pointPaint.color = current.colors[index]
            canvas.drawPoint(sx, screenY, pointPaint)
        }
        if (showCandidates) current.candidates.forEach { candidate ->
            val x = candidate.x - centerX
            val y = candidate.y - centerY
            val z = candidate.z - centerZ
            val rx = cy * x - sy * y
            val ry = sy * x + cy * y
            val py = cp * ry - sp * z
            val sx = width * 0.5f + rx * scale
            val screenY = height * 0.53f - py * scale
            markerPaint.color = when (candidate.detector) {
                "V50" -> Color.RED
                "CAMERA" -> Color.CYAN
                else -> Color.YELLOW
            }
            canvas.drawCircle(sx, screenY, 9f, markerPaint)
        }
        canvas.drawText(
            context.getString(
                if (showCandidates) R.string.point_cloud_overlay_candidates else R.string.point_cloud_overlay_hidden,
                current.size,
            ),
            20f,
            height - 48f,
            textPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                yaw += (event.x - lastX) * 0.008f
                pitch = (pitch + (event.y - lastY) * 0.008f).coerceIn(-(Math.PI / 2).toFloat(), (Math.PI / 2).toFloat())
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun calculateBounds(value: V86PointCloud) {
        val xs = FloatArray(value.size)
        val ys = FloatArray(value.size)
        val zs = FloatArray(value.size)
        for (index in 0 until value.size) {
            xs[index] = value.xyz[index * 3]
            ys[index] = value.xyz[index * 3 + 1]
            zs[index] = value.xyz[index * 3 + 2]
        }
        xs.sort(); ys.sort(); zs.sort()
        val lower = if (value.size >= 100) ((value.size - 1) * 0.02f).toInt() else 0
        val upper = if (value.size >= 100) ((value.size - 1) * 0.98f).toInt() else value.size - 1
        val minX = xs[lower]; val maxX = xs[upper]
        val minY = ys[lower]; val maxY = ys[upper]
        val minZ = zs[lower]; val maxZ = zs[upper]
        centerX = (minX + maxX) * 0.5f
        centerY = (minY + maxY) * 0.5f
        centerZ = (minZ + maxZ) * 0.5f
        spanX = max(1f, maxX - minX)
        spanY = max(1f, maxY - minY)
        spanZ = max(1f, maxZ - minZ)
    }
}
