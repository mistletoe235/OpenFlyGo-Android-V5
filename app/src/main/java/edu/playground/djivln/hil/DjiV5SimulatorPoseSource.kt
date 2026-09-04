package edu.playground.djivln.hil

import android.os.SystemClock
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.SimulatorState
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos

data class SimulatorPoseSample(
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val originLatitude: Double,
    val originLongitude: Double,
    val eastMeters: Double,
    val northMeters: Double,
    val upMeters: Double,
    val rollDegrees: Double,
    val pitchDegrees: Double,
    val yawDegrees: Double,
    val velocityEastMetersPerSecond: Double,
    val velocityNorthMetersPerSecond: Double,
    val velocityUpMetersPerSecond: Double,
    val motorsOn: Boolean,
    val flying: Boolean,
    val measuredRateHz: Double,
)

interface SimulatorPoseSource : AutoCloseable {
    fun start(listener: (SimulatorPoseSample) -> Unit)
    fun stop()
    fun latest(): SimulatorPoseSample?
}

/**
 * V5 does not expose the V4 Simulator update-frequency parameter. SimulatorStatusListener is used
 * as the simulator/local-frame anchor, while a read-only scheduler republishes the latest flight
 * controller telemetry at the requested HIL cadence. No flight command is generated here.
 */
class DjiV5SimulatorPoseSource(
    private val manager: SimulatorManager = SimulatorManager.getInstance(),
) : SimulatorPoseSource {
    data class Diagnostics(
        val rawCallbackCount: Long,
        val rawValueChangeCount: Long,
        val telemetryChangeCount: Long,
        val locationCallbackCount: Long,
        val attitudeCallbackCount: Long,
        val velocityCallbackCount: Long,
        val headingCallbackCount: Long,
        val pollSampleCount: Long,
        val outputRateHz: Double,
        val lastSource: String,
    )

    private val running = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val latest = AtomicReference<SimulatorPoseSample?>()
    private val latestTelemetry = AtomicReference<AircraftSnapshot?>()
    private val latestRawPose = AtomicReference<SimulatorRawPose?>()
    private val previousRawPose = AtomicReference<SimulatorRawPose?>()
    private val rawCallbackCount = AtomicLong(0L)
    private val rawValueChangeCount = AtomicLong(0L)
    private val telemetryChangeCount = AtomicLong(0L)
    private val locationCallbackCount = AtomicLong(0L)
    private val attitudeCallbackCount = AtomicLong(0L)
    private val velocityCallbackCount = AtomicLong(0L)
    private val headingCallbackCount = AtomicLong(0L)
    private val pollSampleCount = AtomicLong(0L)
    private val lastLocationTimestampNanos = AtomicLong(0L)
    private val lastAttitudeTimestampNanos = AtomicLong(0L)
    private val lastVelocityTimestampNanos = AtomicLong(0L)
    private val lastHeadingTimestampNanos = AtomicLong(0L)
    private val rateLock = Any()
    private val pollExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "openfly-v5-simulator-pose").apply { isDaemon = true }
    }
    @Volatile private var callback: ((SimulatorPoseSample) -> Unit)? = null
    @Volatile private var pollFuture: ScheduledFuture<*>? = null
    @Volatile private var outputFrequencyHz = DEFAULT_OUTPUT_FREQUENCY_HZ
    @Volatile private var lastSource = "none"
    private var originLatitude = Double.NaN
    private var originLongitude = Double.NaN
    private var fallbackOriginLatitude = Double.NaN
    private var fallbackOriginLongitude = Double.NaN
    private var rateWindowStartedNanos = 0L
    private var rateWindowSamples = 0L
    private var measuredRateHz = 0.0
    private var lastTelemetryTimestampNanos = 0L

    private val sdkListener = SimulatorStatusListener(::onSimulatorState)

    override fun start(listener: (SimulatorPoseSample) -> Unit) {
        callback = listener
        if (!running.compareAndSet(false, true)) return
        synchronized(rateLock) {
            originLatitude = fallbackOriginLatitude
            originLongitude = fallbackOriginLongitude
            rateWindowStartedNanos = 0L
            rateWindowSamples = 0L
            measuredRateHz = 0.0
            lastTelemetryTimestampNanos = 0L
        }
        rawCallbackCount.set(0L)
        rawValueChangeCount.set(0L)
        telemetryChangeCount.set(0L)
        locationCallbackCount.set(0L)
        attitudeCallbackCount.set(0L)
        velocityCallbackCount.set(0L)
        headingCallbackCount.set(0L)
        pollSampleCount.set(0L)
        lastLocationTimestampNanos.set(0L)
        lastAttitudeTimestampNanos.set(0L)
        lastVelocityTimestampNanos.set(0L)
        lastHeadingTimestampNanos.set(0L)
        sequence.set(0L)
        latest.set(null)
        latestRawPose.set(null)
        previousRawPose.set(null)
        lastSource = "none"
        manager.addSimulatorStateListener(sdkListener)
        restartPoller()
    }

    fun setOutputFrequencyHz(value: Int) {
        outputFrequencyHz = value.coerceIn(MIN_OUTPUT_FREQUENCY_HZ, MAX_OUTPUT_FREQUENCY_HZ)
        if (running.get()) restartPoller()
    }

    fun setFallbackOrigin(latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        synchronized(rateLock) {
            fallbackOriginLatitude = latitude
            fallbackOriginLongitude = longitude
            if (!originLatitude.isFinite() || !originLongitude.isFinite()) {
                originLatitude = latitude
                originLongitude = longitude
            }
        }
    }

    fun refreshListener(): Boolean {
        if (!running.get()) return false
        return runCatching {
            manager.removeSimulatorStateListener(sdkListener)
            manager.addSimulatorStateListener(sdkListener)
            true
        }.getOrDefault(false)
    }

    fun submitTelemetry(snapshot: AircraftSnapshot) {
        latestTelemetry.set(snapshot)
        countTimestampAdvance(
            snapshot.aircraftLocationUpdatedAtNanos,
            lastLocationTimestampNanos,
            locationCallbackCount,
        )
        countTimestampAdvance(
            snapshot.attitudeUpdatedAtNanos,
            lastAttitudeTimestampNanos,
            attitudeCallbackCount,
        )
        countTimestampAdvance(
            snapshot.velocityUpdatedAtNanos,
            lastVelocityTimestampNanos,
            velocityCallbackCount,
        )
        countTimestampAdvance(
            snapshot.headingUpdatedAtNanos,
            lastHeadingTimestampNanos,
            headingCallbackCount,
        )
        val timestamp = snapshot.flightStateUpdatedAtNanos
        if (timestamp <= 0L) return
        synchronized(rateLock) {
            if (timestamp <= lastTelemetryTimestampNanos) return
            lastTelemetryTimestampNanos = timestamp
        }
        telemetryChangeCount.incrementAndGet()
    }

    fun diagnostics(): Diagnostics = Diagnostics(
        rawCallbackCount = rawCallbackCount.get(),
        rawValueChangeCount = rawValueChangeCount.get(),
        telemetryChangeCount = telemetryChangeCount.get(),
        locationCallbackCount = locationCallbackCount.get(),
        attitudeCallbackCount = attitudeCallbackCount.get(),
        velocityCallbackCount = velocityCallbackCount.get(),
        headingCallbackCount = headingCallbackCount.get(),
        pollSampleCount = pollSampleCount.get(),
        outputRateHz = synchronized(rateLock) { measuredRateHz },
        lastSource = lastSource,
    )

    override fun stop() {
        pollFuture?.cancel(false)
        pollFuture = null
        if (running.compareAndSet(true, false)) manager.removeSimulatorStateListener(sdkListener)
        callback = null
        latest.set(null)
        latestTelemetry.set(null)
        latestRawPose.set(null)
        previousRawPose.set(null)
    }

    override fun latest(): SimulatorPoseSample? = latest.get()

    override fun close() {
        stop()
        pollExecutor.shutdownNow()
    }

    private fun restartPoller() {
        pollFuture?.cancel(false)
        if (!running.get()) return
        val periodNanos = 1_000_000_000L / outputFrequencyHz.coerceAtLeast(1)
        pollFuture = pollExecutor.scheduleAtFixedRate(
            ::publishPolledTelemetry,
            0L,
            periodNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    private fun publishPolledTelemetry() {
        if (!running.get()) return
        val now = SystemClock.elapsedRealtimeNanos()
        val raw = latestRawPose.get()
        if (raw != null && now - raw.elapsedRealtimeNanos <= RAW_FRESH_NANOS) {
            val predicted = SimulatorPosePredictor.predict(previousRawPose.get(), raw, now)
            pollSampleCount.incrementAndGet()
            publish(
                timestampNanos = now,
                originLatitude = predicted.originLatitude,
                originLongitude = predicted.originLongitude,
                eastMeters = predicted.eastMeters,
                northMeters = predicted.northMeters,
                upMeters = predicted.upMeters,
                rollDegrees = predicted.rollDegrees,
                pitchDegrees = predicted.pitchDegrees,
                yawDegrees = predicted.yawDegrees,
                velocityEastMetersPerSecond = predicted.velocityEastMetersPerSecond,
                velocityNorthMetersPerSecond = predicted.velocityNorthMetersPerSecond,
                velocityUpMetersPerSecond = predicted.velocityUpMetersPerSecond,
                motorsOn = predicted.motorsOn,
                flying = predicted.flying,
                source = if (predicted.extrapolated) "simulator-state-predicted" else "simulator-state-raw",
            )
            return
        }
        val snapshot = latestTelemetry.get() ?: return
        if (!snapshot.connected || (!snapshot.simulatorActive && !manager.isSimulatorEnabled())) return
        val location = snapshot.aircraftLocation ?: return
        val attitude = snapshot.attitude ?: return
        val origin = synchronized(rateLock) { originLatitude to originLongitude }
        if (!origin.first.isFinite() || !origin.second.isFinite()) return
        val longitudeScale = METERS_PER_LONGITUDE_DEGREE * cos(Math.toRadians(origin.first))
        val east = (location.longitude - origin.second) * longitudeScale
        val north = (location.latitude - origin.first) * METERS_PER_LATITUDE_DEGREE
        val up = snapshot.relativeAltitudeMeters ?: latest.get()?.upMeters ?: location.altitudeMeters ?: 0.0
        pollSampleCount.incrementAndGet()
        publish(
            timestampNanos = now,
            originLatitude = origin.first,
            originLongitude = origin.second,
            eastMeters = east,
            northMeters = north,
            upMeters = up,
            rollDegrees = attitude.roll,
            pitchDegrees = attitude.pitch,
            yawDegrees = snapshot.headingDegrees ?: attitude.yaw,
            velocityEastMetersPerSecond = snapshot.velocity?.east ?: 0.0,
            velocityNorthMetersPerSecond = snapshot.velocity?.north ?: 0.0,
            velocityUpMetersPerSecond = snapshot.velocity?.up ?: 0.0,
            motorsOn = snapshot.motorsOn,
            flying = snapshot.isFlying,
            source = "flight-controller-poll",
        )
    }

    private fun onSimulatorState(state: SimulatorState) {
        if (!running.get()) return
        rawCallbackCount.incrementAndGet()
        val now = SystemClock.elapsedRealtimeNanos()
        val converted = DjiV5SimulatorCoordinates.fromSdk(
            state.positionX,
            state.positionY,
            state.positionZ,
            state.roll,
            state.pitch,
        )
        val location = state.location
        val origin = synchronized(rateLock) {
            if (!originLatitude.isFinite() && location != null &&
                location.latitude.isFinite() && location.longitude.isFinite()
            ) {
                originLatitude = location.latitude - converted.northMeters / METERS_PER_LATITUDE_DEGREE
                val longitudeScale = METERS_PER_LONGITUDE_DEGREE * cos(Math.toRadians(location.latitude))
                originLongitude = location.longitude - converted.eastMeters / longitudeScale
            }
            originLatitude to originLongitude
        }
        if (!origin.first.isFinite() || !origin.second.isFinite()) return
        val raw = SimulatorRawPose(
            elapsedRealtimeNanos = now,
            originLatitude = origin.first,
            originLongitude = origin.second,
            eastMeters = converted.eastMeters,
            northMeters = converted.northMeters,
            upMeters = converted.upMeters,
            rollDegrees = converted.rollDegrees,
            pitchDegrees = converted.pitchDegrees,
            yawDegrees = state.yaw.toDouble(),
            motorsOn = state.areMotorsOn(),
            flying = state.isFlying(),
        )
        val previous = latestRawPose.getAndSet(raw)
        previousRawPose.set(previous)
        if (previous == null || !raw.hasSameStateAs(previous)) {
            rawValueChangeCount.incrementAndGet()
        }
    }

    private fun countTimestampAdvance(
        timestampNanos: Long,
        lastTimestampNanos: AtomicLong,
        counter: AtomicLong,
    ) {
        if (timestampNanos <= 0L) return
        while (true) {
            val previous = lastTimestampNanos.get()
            if (timestampNanos <= previous) return
            if (lastTimestampNanos.compareAndSet(previous, timestampNanos)) {
                counter.incrementAndGet()
                return
            }
        }
    }

    private fun publish(
        timestampNanos: Long,
        originLatitude: Double,
        originLongitude: Double,
        eastMeters: Double,
        northMeters: Double,
        upMeters: Double,
        rollDegrees: Double,
        pitchDegrees: Double,
        yawDegrees: Double,
        velocityEastMetersPerSecond: Double,
        velocityNorthMetersPerSecond: Double,
        velocityUpMetersPerSecond: Double,
        motorsOn: Boolean,
        flying: Boolean,
        source: String,
    ) {
        val currentRate = synchronized(rateLock) {
            if (rateWindowStartedNanos == 0L) rateWindowStartedNanos = timestampNanos
            rateWindowSamples += 1L
            val elapsed = timestampNanos - rateWindowStartedNanos
            if (elapsed >= RATE_WINDOW_NANOS) {
                measuredRateHz = rateWindowSamples * 1_000_000_000.0 / elapsed
                rateWindowStartedNanos = timestampNanos
                rateWindowSamples = 0L
            }
            measuredRateHz
        }
        val sample = SimulatorPoseSample(
            sequence = sequence.incrementAndGet(),
            elapsedRealtimeNanos = timestampNanos,
            originLatitude = originLatitude,
            originLongitude = originLongitude,
            eastMeters = eastMeters,
            northMeters = northMeters,
            upMeters = upMeters,
            rollDegrees = rollDegrees,
            pitchDegrees = pitchDegrees,
            yawDegrees = yawDegrees,
            velocityEastMetersPerSecond = velocityEastMetersPerSecond,
            velocityNorthMetersPerSecond = velocityNorthMetersPerSecond,
            velocityUpMetersPerSecond = velocityUpMetersPerSecond,
            motorsOn = motorsOn,
            flying = flying,
            measuredRateHz = currentRate,
        )
        lastSource = source
        latest.set(sample)
        runCatching { callback?.invoke(sample) }
    }

    private companion object {
        const val RATE_WINDOW_NANOS = 1_000_000_000L
        const val RAW_FRESH_NANOS = 100_000_000L
        const val DEFAULT_OUTPUT_FREQUENCY_HZ = 50
        const val MIN_OUTPUT_FREQUENCY_HZ = 2
        const val MAX_OUTPUT_FREQUENCY_HZ = 150
        const val METERS_PER_LATITUDE_DEGREE = 111_132.0
        const val METERS_PER_LONGITUDE_DEGREE = 111_320.0
    }
}

internal data class SimulatorRawPose(
    val elapsedRealtimeNanos: Long,
    val originLatitude: Double,
    val originLongitude: Double,
    val eastMeters: Double,
    val northMeters: Double,
    val upMeters: Double,
    val rollDegrees: Double,
    val pitchDegrees: Double,
    val yawDegrees: Double,
    val motorsOn: Boolean,
    val flying: Boolean,
) {
    fun hasSameStateAs(other: SimulatorRawPose): Boolean =
        originLatitude == other.originLatitude &&
            originLongitude == other.originLongitude &&
            eastMeters == other.eastMeters &&
            northMeters == other.northMeters &&
            upMeters == other.upMeters &&
            rollDegrees == other.rollDegrees &&
            pitchDegrees == other.pitchDegrees &&
            yawDegrees == other.yawDegrees &&
            motorsOn == other.motorsOn &&
            flying == other.flying
}

internal data class PredictedSimulatorPose(
    val originLatitude: Double,
    val originLongitude: Double,
    val eastMeters: Double,
    val northMeters: Double,
    val upMeters: Double,
    val rollDegrees: Double,
    val pitchDegrees: Double,
    val yawDegrees: Double,
    val velocityEastMetersPerSecond: Double,
    val velocityNorthMetersPerSecond: Double,
    val velocityUpMetersPerSecond: Double,
    val motorsOn: Boolean,
    val flying: Boolean,
    val extrapolated: Boolean,
)

internal object SimulatorPosePredictor {
    private const val MAX_EXTRAPOLATION_NANOS = 35_000_000L
    private const val MAX_LINEAR_SPEED_METERS_PER_SECOND = 30.0
    private const val MAX_ANGULAR_SPEED_DEGREES_PER_SECOND = 360.0

    fun predict(previous: SimulatorRawPose?, current: SimulatorRawPose, targetNanos: Long): PredictedSimulatorPose {
        val sampleDeltaNanos = previous?.let { current.elapsedRealtimeNanos - it.elapsedRealtimeNanos } ?: 0L
        val predictionNanos = (targetNanos - current.elapsedRealtimeNanos).coerceIn(0L, MAX_EXTRAPOLATION_NANOS)
        if (previous == null || sampleDeltaNanos <= 0L) return current.predicted(false)
        val sampleSeconds = sampleDeltaNanos / 1_000_000_000.0
        val predictionSeconds = predictionNanos / 1_000_000_000.0
        fun rate(before: Double, latest: Double, maxRate: Double): Double =
            ((latest - before) / sampleSeconds).coerceIn(-maxRate, maxRate)
        fun extrapolateAngle(before: Double, latest: Double): Double {
            val delta = ((latest - before + 540.0) % 360.0) - 180.0
            val rate = (delta / sampleSeconds).coerceIn(
                -MAX_ANGULAR_SPEED_DEGREES_PER_SECOND,
                MAX_ANGULAR_SPEED_DEGREES_PER_SECOND,
            )
            return normalizeAngle(latest + rate * predictionSeconds)
        }
        val eastRate = rate(previous.eastMeters, current.eastMeters, MAX_LINEAR_SPEED_METERS_PER_SECOND)
        val northRate = rate(previous.northMeters, current.northMeters, MAX_LINEAR_SPEED_METERS_PER_SECOND)
        val upRate = rate(previous.upMeters, current.upMeters, MAX_LINEAR_SPEED_METERS_PER_SECOND)
        return PredictedSimulatorPose(
            originLatitude = current.originLatitude,
            originLongitude = current.originLongitude,
            eastMeters = current.eastMeters + eastRate * predictionSeconds,
            northMeters = current.northMeters + northRate * predictionSeconds,
            upMeters = current.upMeters + upRate * predictionSeconds,
            rollDegrees = extrapolateAngle(previous.rollDegrees, current.rollDegrees),
            pitchDegrees = extrapolateAngle(previous.pitchDegrees, current.pitchDegrees),
            yawDegrees = extrapolateAngle(previous.yawDegrees, current.yawDegrees),
            velocityEastMetersPerSecond = eastRate,
            velocityNorthMetersPerSecond = northRate,
            velocityUpMetersPerSecond = upRate,
            motorsOn = current.motorsOn,
            flying = current.flying,
            extrapolated = true,
        )
    }

    private fun SimulatorRawPose.predicted(extrapolated: Boolean) = PredictedSimulatorPose(
        originLatitude = originLatitude,
        originLongitude = originLongitude,
        eastMeters = eastMeters,
        northMeters = northMeters,
        upMeters = upMeters,
        rollDegrees = rollDegrees,
        pitchDegrees = pitchDegrees,
        yawDegrees = yawDegrees,
        velocityEastMetersPerSecond = 0.0,
        velocityNorthMetersPerSecond = 0.0,
        velocityUpMetersPerSecond = 0.0,
        motorsOn = motorsOn,
        flying = flying,
        extrapolated = extrapolated,
    )

    private fun normalizeAngle(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

/** Dedicated V5 boundary; keep isolated until each aircraft/firmware passes the axis regression. */
object DjiV5SimulatorCoordinates {
    data class EnuFru(
        val eastMeters: Double,
        val northMeters: Double,
        val upMeters: Double,
        val rollDegrees: Double,
        val pitchDegrees: Double,
    )

    fun fromSdk(
        positionX: Number,
        positionY: Number,
        positionZ: Number,
        roll: Number,
        pitch: Number,
    ) = EnuFru(
        northMeters = positionX.toDouble(),
        eastMeters = positionY.toDouble(),
        upMeters = -positionZ.toDouble(),
        rollDegrees = roll.toDouble(),
        pitchDegrees = -pitch.toDouble(),
    )
}
