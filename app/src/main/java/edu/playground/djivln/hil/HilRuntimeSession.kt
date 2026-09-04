package edu.playground.djivln.hil

import android.content.Context
import edu.playground.djivln.domain.camera.CameraFrameSource
import edu.playground.djivln.domain.flight.BodyVelocityCommand
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.control.ProcessVirtualStickPortLease
import java.util.concurrent.CopyOnWriteArraySet

class HilRuntimeSession(
    private val clockNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
    private val virtualStickActive: () -> Boolean = {
        ProcessVirtualStickPortLease.instance.snapshot().let {
            it.stateObserved && it.actualEnabled && it.actualOwnedByApp
        }
    },
    context: Context? = null,
) : AndroidHilController.Listener, AutoCloseable {
    interface Listener : AndroidHilController.Listener

    data class SimulatorSourceStatus(
        val received: Boolean = false,
        val ageMillis: Long? = null,
        val measuredRateHz: Double = 0.0,
    )

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val controller = AndroidHilController(this, context)
    private var latestCommand = BodyVelocityCommand.ZERO
    private var latestTelemetry = AircraftSnapshot()
    private var lastSimulatorSampleNanos = 0L
    private var measuredSimulatorRateHz = 0.0
    @Volatile private var latestStatus: AndroidHilController.Status? = null

    @Synchronized
    fun start(config: AndroidHilController.Config): Boolean {
        resetRuntimeState()
        controller.start(config)
        return controller.isRunning()
    }

    @Synchronized
    fun stop() {
        controller.stop()
        resetRuntimeState()
    }

    fun stopForSafety(reason: String) {
        listeners.forEach { it.onHilError(reason) }
        listeners.forEach(Listener::onHilLinkStale)
        stop()
    }
    fun status(): AndroidHilController.Status? = latestStatus
    fun decodeLatestFrame(maxAgeMillis: Long) = controller.decodeLatestFrame(maxAgeMillis)
    fun latestFrame(maxAgeMillis: Long) = controller.latestFrame(maxAgeMillis)
    fun cameraSource(): CameraFrameSource = HilCameraFrameSource(controller)

    @Synchronized
    fun simulatorSourceStatus(): SimulatorSourceStatus {
        if (lastSimulatorSampleNanos <= 0L) return SimulatorSourceStatus()
        val age = ((clockNanos() - lastSimulatorSampleNanos).coerceAtLeast(0L) / 1_000_000L)
        return SimulatorSourceStatus(true, age, measuredSimulatorRateHz)
    }

    fun simulatorSourceFresh(maxAgeMillis: Long = SIMULATOR_SOURCE_FRESH_MILLIS): Boolean =
        simulatorSourceStatus().ageMillis?.let { it <= maxAgeMillis } == true

    fun addListener(listener: Listener) {
        listeners += listener
        latestStatus?.let(listener::onHilStatus)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @Synchronized
    fun updateCommand(command: BodyVelocityCommand) {
        latestCommand = command
    }

    @Synchronized
    fun submitTelemetry(snapshot: AircraftSnapshot) {
        latestTelemetry = snapshot
        // openfly.hil.v1 is a DJI-Simulator HIL link. Ordinary real-aircraft telemetry is
        // auxiliary metadata only and must never be promoted to the authoritative HIL pose.
    }

    @Synchronized
    fun submitSimulatorPose(sample: SimulatorPoseSample) {
        if (sample.elapsedRealtimeNanos <= lastSimulatorSampleNanos) return
        lastSimulatorSampleNanos = sample.elapsedRealtimeNanos
        measuredSimulatorRateHz = sample.measuredRateHz
        val telemetry = latestTelemetry
        val now = clockNanos()
        val ageMillis = telemetry.flightStateUpdatedAtNanos.takeIf { it > 0L }?.let {
            ((now - it).coerceAtLeast(0L) / 1_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } ?: Int.MAX_VALUE
        val flags = (if (sample.motorsOn) HilProtocol.POSE_FLAG_MOTORS_ON else 0) or
            (if (sample.flying) HilProtocol.POSE_FLAG_FLYING else 0) or
            (if (virtualStickActive()) HilProtocol.POSE_FLAG_VIRTUAL_STICK else 0)
        controller.submitPose(HilProtocol.Pose(
            sampleMonotonicNanos = sample.elapsedRealtimeNanos,
            originLatitudeDegrees = sample.originLatitude,
            originLongitudeDegrees = sample.originLongitude,
            eastMeters = sample.eastMeters,
            northMeters = sample.northMeters,
            upMeters = sample.upMeters,
            rollDegrees = sample.rollDegrees,
            pitchDegrees = sample.pitchDegrees,
            headingDegreesClockwiseFromNorth = sample.yawDegrees,
            // SimulatorState does not expose velocity directly. Derive it from consecutive RAW
            // local-frame positions so HIL velocity has the same cadence and time base as pose.
            velocityNorthMetersPerSecond = sample.velocityNorthMetersPerSecond,
            velocityEastMetersPerSecond = sample.velocityEastMetersPerSecond,
            velocityUpMetersPerSecond = sample.velocityUpMetersPerSecond,
            gimbalPitchDegrees = telemetry.gimbalPitchDegrees ?: 0.0,
            commandForwardMetersPerSecond = latestCommand.forwardMetersPerSecond,
            commandRightMetersPerSecond = latestCommand.rightMetersPerSecond,
            commandUpMetersPerSecond = latestCommand.upMetersPerSecond,
            commandYawRateDegreesPerSecond = latestCommand.yawRateDegreesPerSecond,
            flightStateAgeMillis = ageMillis,
            measuredSimulatorHz = sample.measuredRateHz.toFloat(),
            stateFlags = flags,
        ))
    }

    override fun onHilEvent(event: HilProtocol.Event) = listeners.forEach { it.onHilEvent(event) }
    override fun onHilLinkStale() = listeners.forEach(Listener::onHilLinkStale)
    override fun onHilError(message: String) = listeners.forEach { it.onHilError(message) }
    override fun onHilStatus(status: AndroidHilController.Status) {
        latestStatus = status
        listeners.forEach { it.onHilStatus(status) }
    }

    override fun close() {
        listeners.clear()
        controller.close()
        synchronized(this) { resetRuntimeState() }
    }

    private fun resetRuntimeState() {
        latestCommand = BodyVelocityCommand.ZERO
        latestTelemetry = AircraftSnapshot()
        lastSimulatorSampleNanos = 0L
        measuredSimulatorRateHz = 0.0
        latestStatus = null
    }

    companion object {
        const val SIMULATOR_SOURCE_FRESH_MILLIS = 1_000L
    }
}
