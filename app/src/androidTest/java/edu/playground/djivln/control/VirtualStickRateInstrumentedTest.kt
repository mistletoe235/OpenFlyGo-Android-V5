package edu.playground.djivln.control

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.SimulatorState
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.NextMainActivity
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware-backed comparison that is fail-closed unless DJI SimulatorManager is enabled. */
@RunWith(AndroidJUnit4::class)
class VirtualStickRateInstrumentedTest {
    private val simulatorManager = SimulatorManager.getInstance()
    private val virtualStickManager = VirtualStickManager.getInstance()
    private val latestState = AtomicReference<SimulatorState?>()
    private val latestVirtualStickState = AtomicReference<VirtualStickState?>()

    private val simulatorListener = SimulatorStatusListener { latestState.set(it) }
    private val virtualStickListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            latestVirtualStickState.set(state)
        }

        override fun onChangeReasonUpdate(reason: dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason) = Unit
    }

    data class Result(
        val requestedHz: Int,
        val measuredHz: Double,
        val sent: Int,
        val firstMovementMs: Double?,
        val displacementMeters: Double,
    )

    @Test(timeout = 180_000L)
    fun compare25And50HzInDjiSimulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bootstrapListener: (DjiSdkBootstrap.State) -> Unit = { state ->
            Log.i(
                TAG,
                "SDK initialized=${state.initialized} manager=${state.managerInitialized} " +
                    "registered=${state.registered} connected=${state.connected} " +
                    "event=${state.initEvent}/${state.initProgress} error=${state.lastError ?: "--"}",
            )
        }
        DjiSdkBootstrap.addListener(bootstrapListener)
        simulatorManager.addSimulatorStateListener(simulatorListener)
        virtualStickManager.setVirtualStickStateListener(virtualStickListener)
        var simulatorStartedHere = false
        try {
            // Flight UI continuously renders video/telemetry and therefore never becomes
            // "idle" enough for startActivitySync(). An async launch is the correct contract.
            instrumentation.targetContext.startActivity(
                Intent(instrumentation.targetContext, NextMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            DjiSdkBootstrap.registerApp()
            assertTrue(
                "DJI SDK did not register: ${DjiSdkBootstrap.snapshot()}",
                waitUntil(45_000L) { DjiSdkBootstrap.snapshot().registered },
            )
            assertTrue(
                "DJI SDK/aircraft did not connect: ${DjiSdkBootstrap.snapshot()}",
                waitUntil(45_000L) { DjiSdkBootstrap.snapshot().connected },
            )
            if (!simulatorManager.isSimulatorEnabled()) {
                val settings = InitializationSettings.createInstance(
                    LocationCoordinate2D(31.182825, 121.473651),
                    12,
                )
                awaitCompletion("enable simulator", 20_000L) { callback ->
                    simulatorManager.enableSimulator(settings, callback)
                }
                simulatorStartedHere = true
            }
            assertTrue("SimulatorManager did not remain enabled", waitUntil(10_000L) { simulatorManager.isSimulatorEnabled() })
            assertSimulator("before takeoff")

            if (latestState.get()?.isFlying != true) {
                awaitAction("takeoff", FlightControllerKey.KeyStartTakeoff)
            }
            assertTrue(
                "simulator aircraft did not become airborne",
                waitUntil(35_000L) {
                    val state = latestState.get()
                    simulatorManager.isSimulatorEnabled() && state?.isFlying == true && kotlin.math.abs(state.positionZ) >= 0.8f
                },
            )
            // isFlying/height become true before the FC leaves its protected TAKEOFF phase.
            // Let hover settle, then retry only this authority request; no command is sent yet.
            SystemClock.sleep(8_000L)
            var virtualStickEnableError: String? = null
            for (attempt in 1..5) {
                assertSimulator("before Virtual Stick enable attempt $attempt")
                virtualStickEnableError = awaitCompletionResult(12_000L) { callback ->
                    virtualStickManager.enableVirtualStick(callback)
                }
                if (virtualStickEnableError == null) break
                Log.w(TAG, "Virtual Stick enable attempt $attempt failed: $virtualStickEnableError")
                SystemClock.sleep(3_000L)
            }
            check(virtualStickEnableError == null) {
                "enable Virtual Stick failed after settle/retry: $virtualStickEnableError"
            }
            virtualStickManager.setVirtualStickAdvancedModeEnabled(true)
            assertTrue(
                "Virtual Stick advanced mode was not confirmed",
                waitUntil(10_000L) {
                    latestVirtualStickState.get()?.let {
                        it.isVirtualStickEnable && it.isVirtualStickAdvancedModeEnabled
                    } == true
                },
            )
            stream(25, 0.0, 800L)

            val results = listOf(
                runSegment(25, 0.5),
                runSegment(50, -0.5),
                runSegment(50, 0.5),
                runSegment(25, -0.5),
            )
            results.forEach { result ->
                Log.i(
                    TAG,
                    "VS_RATE_AB RESULT=PASS requested=${result.requestedHz}Hz " +
                        "sent=${result.sent} measured=${fmt(result.measuredHz)}Hz " +
                        "firstMovementMs=${result.firstMovementMs?.let(::fmt) ?: "--"} " +
                        "displacementM=${fmt(result.displacementMeters)}",
                )
            }
            listOf(25, 50).forEach { hz ->
                val group = results.filter { it.requestedHz == hz }
                Log.i(
                    TAG,
                    "VS_RATE_AB SUMMARY requested=${hz}Hz runs=${group.size} " +
                        "measuredMean=${fmt(group.map { it.measuredHz }.average())}Hz " +
                        "movementMeanMs=${fmt(group.mapNotNull { it.firstMovementMs }.average())} " +
                        "displacementMeanM=${fmt(group.map { it.displacementMeters }.average())}",
                )
            }
        } finally {
            if (simulatorManager.isSimulatorEnabled()) {
                runCatching { stream(25, 0.0, 400L) }
                runCatching { virtualStickManager.setVirtualStickAdvancedModeEnabled(false) }
                awaitCompletionIgnoringFailure(5_000L) { callback -> virtualStickManager.disableVirtualStick(callback) }
                if (latestState.get()?.isFlying == true) {
                    runCatching { awaitAction("landing", FlightControllerKey.KeyStartAutoLanding) }
                    waitUntil(35_000L) { latestState.get()?.let { !it.isFlying && !it.areMotorsOn() } == true }
                }
                if (simulatorStartedHere) {
                    awaitCompletionIgnoringFailure(10_000L) { callback -> simulatorManager.disableSimulator(callback) }
                }
            }
            virtualStickManager.removeVirtualStickStateListener(virtualStickListener)
            simulatorManager.removeSimulatorStateListener(simulatorListener)
            DjiSdkBootstrap.removeListener(bootstrapListener)
        }
    }

    private fun runSegment(hz: Int, forwardMetersPerSecond: Double): Result {
        assertSimulator("before ${hz}Hz segment")
        stream(hz, 0.0, 800L)
        val start = latestState.get() ?: error("No simulator state before ${hz}Hz segment")
        val startX = start.positionX
        val startY = start.positionY
        val startZ = start.positionZ
        val segmentStart = SystemClock.elapsedRealtimeNanos()
        var firstMovementNanos: Long? = null
        var firstSend = 0L
        var lastSend = 0L
        var sent = 0
        val intervalNanos = 1_000_000_000L / hz
        val deadline = segmentStart + SEGMENT_MILLIS * 1_000_000L
        var next = segmentStart
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            assertSimulator("during ${hz}Hz segment")
            val now = SystemClock.elapsedRealtimeNanos()
            if (firstSend == 0L) firstSend = now
            lastSend = now
            virtualStickManager.sendVirtualStickAdvancedParam(param(forwardMetersPerSecond))
            sent += 1
            val state = latestState.get()
            if (firstMovementNanos == null && state != null && distance(startX, startY, startZ, state) >= 0.02) {
                firstMovementNanos = now
            }
            next += intervalNanos
            LockSupport.parkNanos((next - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L))
        }
        virtualStickManager.sendVirtualStickAdvancedParam(param(0.0))
        val end = latestState.get() ?: error("No simulator state after ${hz}Hz segment")
        val measured = if (sent > 1 && lastSend > firstSend) {
            (sent - 1) * 1_000_000_000.0 / (lastSend - firstSend)
        } else {
            0.0
        }
        val result = Result(
            requestedHz = hz,
            measuredHz = measured,
            sent = sent,
            firstMovementMs = firstMovementNanos?.let { (it - segmentStart) / 1_000_000.0 },
            displacementMeters = distance(startX, startY, startZ, end),
        )
        SystemClock.sleep(1_200L)
        return result
    }

    private fun stream(hz: Int, forwardMetersPerSecond: Double, durationMillis: Long) {
        val intervalNanos = 1_000_000_000L / hz
        val deadline = SystemClock.elapsedRealtimeNanos() + durationMillis * 1_000_000L
        var next = SystemClock.elapsedRealtimeNanos()
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            assertSimulator("during settle stream")
            virtualStickManager.sendVirtualStickAdvancedParam(param(forwardMetersPerSecond))
            next += intervalNanos
            LockSupport.parkNanos((next - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L))
        }
    }

    private fun param(forwardMetersPerSecond: Double) = VirtualStickFlightControlParam().apply {
        rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        rollPitchControlMode = RollPitchControlMode.VELOCITY
        verticalControlMode = VerticalControlMode.VELOCITY
        yawControlMode = YawControlMode.ANGULAR_VELOCITY
        pitch = 0.0
        roll = forwardMetersPerSecond
        verticalThrottle = 0.0
        yaw = 0.0
    }

    private fun distance(x: Float, y: Float, z: Float, state: SimulatorState): Double {
        val dx = state.positionX - x
        val dy = state.positionY - y
        val dz = state.positionZ - z
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }

    private fun assertSimulator(stage: String) {
        check(simulatorManager.isSimulatorEnabled()) { "Simulator disabled $stage; control aborted" }
    }

    private fun awaitAction(
        label: String,
        key: DJIActionKeyInfo<EmptyMsg, EmptyMsg>,
        timeoutMillis: Long = 15_000L,
    ) {
        assertSimulator("before $label")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        KeyManager.getInstance().performAction(
            KeyTools.createKey(key),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg) = latch.countDown()
                override fun onFailure(error: IDJIError) {
                    failure.set(error.toString())
                    latch.countDown()
                }
            },
        )
        check(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "$label callback timed out" }
        check(failure.get() == null) { "$label failed: ${failure.get()}" }
    }

    private fun awaitCompletion(
        label: String,
        timeoutMillis: Long,
        action: (CommonCallbacks.CompletionCallback) -> Unit,
    ) {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        action(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(error: IDJIError) {
                failure.set(error.toString())
                latch.countDown()
            }
        })
        check(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "$label callback timed out" }
        check(failure.get() == null) { "$label failed: ${failure.get()}" }
    }

    private fun awaitCompletionIgnoringFailure(
        timeoutMillis: Long,
        action: (CommonCallbacks.CompletionCallback) -> Unit,
    ) {
        val latch = CountDownLatch(1)
        action(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(error: IDJIError) = latch.countDown()
        })
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun awaitCompletionResult(
        timeoutMillis: Long,
        action: (CommonCallbacks.CompletionCallback) -> Unit,
    ): String? {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        action(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(error: IDJIError) {
                failure.set(error.toString())
                latch.countDown()
            }
        })
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return "callback timed out"
        return failure.get()
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(100L)
        }
        return predicate()
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    companion object {
        private const val TAG = "VsRateIT"
        private const val SEGMENT_MILLIS = 2_000L
    }
}
