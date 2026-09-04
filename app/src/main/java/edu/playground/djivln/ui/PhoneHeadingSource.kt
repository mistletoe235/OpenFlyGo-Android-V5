package edu.playground.djivln.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

internal class PhoneHeadingSource(
    context: Context,
    private val displayRotation: () -> Int,
    private val onHeadingChanged: (Heading) -> Unit,
) : SensorEventListener, AutoCloseable {
    data class Heading(
        val degrees: Double,
        val accuracy: Int,
        val timestampNanos: Long,
    )

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val screenRotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var running = false
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var lastPublishedNanos = 0L
    private var filteredHeadingDegrees = Double.NaN

    fun start(): Boolean {
        if (running) return rotationSensor != null
        val sensor = rotationSensor ?: return false
        running = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
        return running
    }

    fun stop() {
        if (running) sensorManager.unregisterListener(this)
        running = false
        lastPublishedNanos = 0L
        filteredHeadingDegrees = Double.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != rotationSensor?.type) return
        if (lastPublishedNanos > 0L && event.timestamp - lastPublishedNanos < MIN_PUBLISH_INTERVAL_NANOS) return
        // Several vendor rotation-vector implementations publish usable samples without
        // ever issuing an initial onAccuracyChanged callback. Keep the event's accuracy,
        // but do not make map-only direction rendering depend on that callback existing.
        accuracy = event.accuracy
        val heading = runCatching {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val axes = screenAxes(displayRotation())
            check(SensorManager.remapCoordinateSystem(rotationMatrix, axes.first, axes.second, screenRotationMatrix))
            SensorManager.getOrientation(screenRotationMatrix, orientation)
            normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        }.getOrNull() ?: return
        filteredHeadingDegrees = smoothHeading(filteredHeadingDegrees, heading)
        lastPublishedNanos = event.timestamp
        onHeadingChanged(Heading(filteredHeadingDegrees, accuracy, event.timestamp))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == rotationSensor?.type) this.accuracy = accuracy
    }

    override fun close() = stop()

    companion object {
        private const val MIN_PUBLISH_INTERVAL_NANOS = 50_000_000L
        private const val FILTER_ALPHA = 0.22
        const val MAX_HEADING_AGE_NANOS = 2_000_000_000L

        internal fun isUsable(heading: Heading?, nowNanos: Long): Boolean =
            heading != null &&
                isDisplayable(heading, nowNanos) &&
                heading.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE

        /** Map-only fallback: show a fresh finite estimate even when magnetic accuracy is unknown. */
        internal fun isDisplayable(heading: Heading?, nowNanos: Long): Boolean =
            heading != null &&
                heading.degrees.isFinite() &&
                nowNanos >= heading.timestampNanos &&
                nowNanos - heading.timestampNanos <= MAX_HEADING_AGE_NANOS

        internal fun normalizeDegrees(value: Double): Double = (value % 360.0 + 360.0) % 360.0

        internal fun smoothHeading(previous: Double, current: Double): Double {
            if (!previous.isFinite()) return normalizeDegrees(current)
            val delta = (current - previous + 540.0) % 360.0 - 180.0
            return normalizeDegrees(previous + delta * FILTER_ALPHA)
        }

        private fun screenAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }
}
