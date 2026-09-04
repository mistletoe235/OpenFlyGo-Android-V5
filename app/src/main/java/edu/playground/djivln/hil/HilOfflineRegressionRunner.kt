package edu.playground.djivln.hil

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

/** Debug-only Android HIL regression. Never represents its output as DJI SimulatorState. */
class HilOfflineRegressionRunner(private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onLog(message: String)
        fun onFinished(result: Result)
    }

    data class Result(
        val passed: Boolean,
        val sentPoseCount: Long,
        val receivedPoseCount: Long,
        val measuredPoseHz: Double,
        val altitudeMeters: Double,
        val northMeters: Double,
        val maxBrakePitchDegrees: Double,
        val peerFresh: Boolean,
        val reason: String,
    )

    private val running = AtomicBoolean(false)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "openfly-hil-offline-regression").apply { isDaemon = true }
    }
    private val dynamics = MockFlightDynamics()
    private var controller: AndroidHilController? = null
    private var loopbackPeer: HilLoopbackPeer? = null
    private var startedNanos = 0L
    private var sequence = 0L
    private var maxBrakePitchDegrees = 0.0

    fun start(host: String = "127.0.0.1") {
        if (!running.compareAndSet(false, true)) return
        listener.onLog("HIL_OFFLINE START source=MOCK_RC+MOCK_DYNAMICS host=$host")
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) {
            loopbackPeer = HilLoopbackPeer(UDP_SERVER_PORT).also { it.start() }
        }
        val value = AndroidHilController(object : AndroidHilController.Listener {
            override fun onHilEvent(event: HilProtocol.Event) = Unit
            override fun onHilLinkStale() = listener.onLog("HIL_OFFLINE peer heartbeat stale")
            override fun onHilStatus(status: AndroidHilController.Status) = Unit
            override fun onHilError(message: String) = listener.onLog("HIL_OFFLINE error=$message")
        })
        controller = value
        value.start(AndroidHilController.Config(
            mode = HilConnectionMode.LAN,
            host = host,
            udpServerPort = UDP_SERVER_PORT,
            udpLocalPort = UDP_LOCAL_PORT,
            frameTcpPort = FRAME_TCP_PORT,
            poseSendHz = POSE_HZ,
            simulatorStateHz = POSE_HZ,
            heartbeatTimeoutMillis = 1_000L,
        ))
        if (!value.isRunning()) {
            finish(false, "offline HIL controller failed to start")
            return
        }
        startedNanos = SystemClock.elapsedRealtimeNanos()
        executor.scheduleAtFixedRate(::tick, 0L, STEP_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (!running.get()) return
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsedSeconds = (now - startedNanos) / 1_000_000_000.0
        val command = scriptedCommand(elapsedSeconds)
        val state = dynamics.step(command, STEP_MILLIS / 1_000.0)
        if (elapsedSeconds in 5.0..6.2) {
            maxBrakePitchDegrees = maxOf(maxBrakePitchDegrees, state.pitchDegrees)
        }
        controller?.submitPose(HilProtocol.Pose(
            sampleMonotonicNanos = now,
            originLatitudeDegrees = 31.1815535,
            originLongitudeDegrees = 121.4736515,
            eastMeters = state.eastMeters,
            northMeters = state.northMeters,
            upMeters = state.upMeters,
            rollDegrees = state.rollDegrees,
            pitchDegrees = state.pitchDegrees,
            headingDegreesClockwiseFromNorth = state.headingDegrees,
            velocityNorthMetersPerSecond = state.velocityNorth,
            velocityEastMetersPerSecond = state.velocityEast,
            velocityUpMetersPerSecond = state.velocityUp,
            gimbalPitchDegrees = 0.0,
            commandForwardMetersPerSecond = command.forward,
            commandRightMetersPerSecond = command.right,
            commandUpMetersPerSecond = command.up,
            commandYawRateDegreesPerSecond = command.yawRate,
            flightStateAgeMillis = 0,
            measuredSimulatorHz = POSE_HZ.toFloat(),
            stateFlags = HilProtocol.POSE_FLAG_MOTORS_ON or
                if (state.upMeters > 0.05) HilProtocol.POSE_FLAG_FLYING else 0,
        ))
        sequence += 1L
        if (sequence % POSE_HZ == 0L) {
            listener.onLog(String.format(
                "HIL_OFFLINE t=%.1fs xyz=%.2f,%.2f,%.2f pitch=%.1f source=MOCK",
                elapsedSeconds, state.eastMeters, state.northMeters, state.upMeters,
                state.pitchDegrees,
            ))
        }
        if (elapsedSeconds >= SCENARIO_SECONDS) {
            val status = controller?.latestStatus()
            val sent = status?.sentPoseCount ?: 0L
            val rate = status?.measuredPoseSendHz ?: 0.0
            val received = loopbackPeer?.poseCount() ?: 0L
            val passed = sent >= 550L && received >= 500L && rate >= 85.0 &&
                status?.peerFresh == true && state.upMeters >= 0.8 &&
                state.northMeters >= 1.0 && maxBrakePitchDegrees >= 3.0
            finish(
                passed,
                "sent=$sent received=$received rate=${"%.1f".format(rate)} " +
                    "altitude=${"%.2f".format(state.upMeters)} " +
                    "north=${"%.2f".format(state.northMeters)} brakePitch=" +
                    "${"%.1f".format(maxBrakePitchDegrees)}",
                state,
            )
        }
    }

    private fun scriptedCommand(elapsedSeconds: Double): Command = when {
        elapsedSeconds < 0.5 -> Command()
        elapsedSeconds < 2.0 -> Command(up = 1.2)
        elapsedSeconds < 3.0 -> Command()
        elapsedSeconds < 5.0 -> Command(forward = 2.0)
        elapsedSeconds < 6.2 -> Command()
        else -> Command(yawRate = 30.0)
    }

    private fun finish(passed: Boolean, reason: String, state: State = dynamics.state()) {
        if (!running.compareAndSet(true, false)) return
        val status = controller?.latestStatus()
        val result = Result(
            passed = passed,
            sentPoseCount = status?.sentPoseCount ?: 0L,
            receivedPoseCount = loopbackPeer?.poseCount() ?: 0L,
            measuredPoseHz = status?.measuredPoseSendHz ?: 0.0,
            altitudeMeters = state.upMeters,
            northMeters = state.northMeters,
            maxBrakePitchDegrees = maxBrakePitchDegrees,
            peerFresh = status?.peerFresh ?: false,
            reason = reason,
        )
        controller?.close()
        controller = null
        loopbackPeer?.close()
        loopbackPeer = null
        executor.shutdownNow()
        listener.onFinished(result)
    }

    override fun close() = finish(false, "cancelled")

    private data class Command(
        val forward: Double = 0.0,
        val right: Double = 0.0,
        val up: Double = 0.0,
        val yawRate: Double = 0.0,
    )

    private data class State(
        val eastMeters: Double,
        val northMeters: Double,
        val upMeters: Double,
        val velocityEast: Double,
        val velocityNorth: Double,
        val velocityUp: Double,
        val rollDegrees: Double,
        val pitchDegrees: Double,
        val headingDegrees: Double,
    )

    private class MockFlightDynamics {
        private var east = 0.0
        private var north = 0.0
        private var up = 0.0
        private var velocityForward = 0.0
        private var velocityRight = 0.0
        private var velocityUp = 0.0
        private var heading = 0.0
        private var pitch = 0.0
        private var roll = 0.0

        fun step(command: Command, dt: Double): State {
            val forwardAcceleration = approachAcceleration(
                velocityForward, command.forward, dt,
                if (abs(command.forward) < 0.01) 4.5 else 2.5,
            )
            val rightAcceleration = approachAcceleration(
                velocityRight, command.right, dt,
                if (abs(command.right) < 0.01) 4.5 else 2.5,
            )
            val upAcceleration = approachAcceleration(
                velocityUp, command.up, dt,
                if (abs(command.up) < 0.01) 3.5 else 2.0,
            )
            velocityForward += forwardAcceleration * dt
            velocityRight += rightAcceleration * dt
            velocityUp += upAcceleration * dt
            heading = normalizeHeading(heading + command.yawRate * dt)
            val yawRadians = Math.toRadians(heading)
            val velocityEast = velocityForward * kotlin.math.sin(yawRadians) +
                velocityRight * kotlin.math.cos(yawRadians)
            val velocityNorth = velocityForward * kotlin.math.cos(yawRadians) -
                velocityRight * kotlin.math.sin(yawRadians)
            east += velocityEast * dt
            north += velocityNorth * dt
            up = (up + velocityUp * dt).coerceAtLeast(0.0)
            if (up == 0.0 && velocityUp < 0.0) velocityUp = 0.0
            val targetPitch = Math.toDegrees(-atan2(
                forwardAcceleration + velocityForward * 0.15, 9.81,
            )).coerceIn(-30.0, 30.0)
            val targetRoll = Math.toDegrees(atan2(
                rightAcceleration + velocityRight * 0.15, 9.81,
            )).coerceIn(-30.0, 30.0)
            val attitudeBlend = 1.0 - exp(-dt / 0.16)
            pitch += (targetPitch - pitch) * attitudeBlend
            roll += (targetRoll - roll) * attitudeBlend
            return state()
        }

        fun state(): State {
            val yawRadians = Math.toRadians(heading)
            return State(
                eastMeters = east,
                northMeters = north,
                upMeters = up,
                velocityEast = velocityForward * kotlin.math.sin(yawRadians) +
                    velocityRight * kotlin.math.cos(yawRadians),
                velocityNorth = velocityForward * kotlin.math.cos(yawRadians) -
                    velocityRight * kotlin.math.sin(yawRadians),
                velocityUp = velocityUp,
                rollDegrees = roll,
                pitchDegrees = pitch,
                headingDegrees = heading,
            )
        }

        private fun approachAcceleration(
            current: Double,
            target: Double,
            dt: Double,
            maxAcceleration: Double,
        ): Double {
            if (dt <= 0.0) return 0.0
            return ((target - current) / dt).coerceIn(-maxAcceleration, maxAcceleration)
        }

        private fun normalizeHeading(value: Double): Double {
            var result = value % 360.0
            if (result < 0.0) result += 360.0
            return result
        }
    }

    private companion object {
        const val UDP_SERVER_PORT = 30_120
        const val UDP_LOCAL_PORT = 30_121
        const val FRAME_TCP_PORT = 30_122
        const val POSE_HZ = 100
        const val STEP_MILLIS = 10L
        const val SCENARIO_SECONDS = 7.2
    }
}
