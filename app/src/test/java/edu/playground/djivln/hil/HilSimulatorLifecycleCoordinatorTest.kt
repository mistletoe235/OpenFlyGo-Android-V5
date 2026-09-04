package edu.playground.djivln.hil

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HilSimulatorLifecycleCoordinatorTest {
    @Test fun activeAirborneSimulatorIsAdoptedWithoutRestart() {
        val fixture = Fixture(enabled = true, rawFresh = true)
        fixture.coordinator.start(ORIGIN, observation(active = true, airborne = true))

        assertEquals(0, fixture.backend.enableCalls)
        assertEquals(0, fixture.backend.disableCalls)
        assertEquals(R.string.simulator_airborne_adopted, fixture.messages.last().resourceId)
    }

    @Test fun groundedResidualSimulatorIsCleanRestarted() {
        val fixture = Fixture(enabled = true, rawFresh = true)
        fixture.coordinator.start(ORIGIN, observation(active = true, airborne = false))

        assertEquals(1, fixture.backend.disableCalls)
        fixture.backend.completeDisable(success = true)
        fixture.scheduler.runAll()
        assertEquals(1, fixture.backend.enableCalls)
    }

    @Test fun enableCallbackTimeoutTrustsActualSdkState() {
        val fixture = Fixture(enabled = false, rawFresh = false)
        fixture.coordinator.start(ORIGIN, observation(active = false, airborne = false))
        assertEquals(1, fixture.backend.enableCalls)

        fixture.backend.enabled = true
        fixture.scheduler.runNext(HilSimulatorLifecycleCoordinator.OPERATION_TIMEOUT_MILLIS)

        assertEquals(1, fixture.backend.enableCalls)
        assertTrue(fixture.backend.refreshCalls > 0)
        assertTrue(fixture.messages.any { it.resourceId == R.string.simulator_callback_timeout_sdk_active })
    }

    @Test fun staleCallbackFromStoppedGenerationIsIgnored() {
        val fixture = Fixture(enabled = false, rawFresh = false)
        fixture.coordinator.start(ORIGIN, observation(active = false, airborne = false))
        fixture.coordinator.stopPreservingSimulator()
        fixture.backend.completeEnable(success = true)

        assertEquals(R.string.simulator_state_preserved, fixture.coordinator.currentMessage().resourceId)
        assertEquals(0, fixture.backend.refreshCalls)
    }

    @Test fun activeSimulatorWithStaleRawOnlyRebindsListener() {
        val fixture = Fixture(enabled = true, rawFresh = false)
        fixture.coordinator.start(ORIGIN, observation(active = true, airborne = false))

        assertEquals(0, fixture.backend.enableCalls)
        assertEquals(0, fixture.backend.disableCalls)
        assertEquals(1, fixture.backend.refreshCalls)
    }

    @Test fun manualDisablePreventsHilFromReenablingSimulator() {
        val fixture = Fixture(enabled = true, rawFresh = false)
        fixture.coordinator.start(ORIGIN, observation(active = true, airborne = false))
        fixture.coordinator.setManualEnabled(false, ORIGIN, observation(active = true, airborne = false)) { _, _ -> }
        fixture.backend.completeDisable(success = true)

        fixture.coordinator.observe(ORIGIN, observation(active = false, airborne = false))

        assertEquals(0, fixture.backend.enableCalls)
        assertEquals(R.string.simulator_manual_closed, fixture.messages.last().resourceId)
    }

    @Test fun manualEnableClearsDisableOverride() {
        val fixture = Fixture(enabled = false, rawFresh = false)
        fixture.coordinator.setManualEnabled(false, ORIGIN, observation(active = false, airborne = false)) { _, _ -> }
        fixture.backend.completeDisable(success = true)
        fixture.coordinator.setManualEnabled(true, ORIGIN, observation(active = false, airborne = false)) { _, _ -> }
        fixture.backend.completeEnable(success = true)

        assertTrue(fixture.backend.enabled)
        assertTrue(fixture.messages.any { it.resourceId == R.string.simulator_manual_start_succeeded })
    }

    @Test fun manuallyDisabledSimulatorIsTurnedOffAgainIfItReappears() {
        val fixture = Fixture(enabled = false, rawFresh = false)
        fixture.coordinator.start(ORIGIN, observation(active = false, airborne = false))
        fixture.coordinator.setManualEnabled(false, ORIGIN, observation(active = false, airborne = false)) { _, _ -> }
        fixture.backend.completeDisable(success = true)

        fixture.backend.enabled = true
        fixture.coordinator.observe(ORIGIN, observation(active = true, airborne = false))

        assertEquals(2, fixture.backend.disableCalls)
        assertEquals(R.string.simulator_restore_manual_disable, fixture.messages.last().resourceId)
    }

    private class Fixture(enabled: Boolean, rawFresh: Boolean) {
        val scheduler = FakeScheduler()
        val backend = FakeBackend(enabled)
        var fresh = rawFresh
        val messages = mutableListOf<UiText>()
        val coordinator = HilSimulatorLifecycleCoordinator(
            backend = backend,
            scheduler = scheduler,
            rawFresh = { fresh },
            refreshRawListener = {
                backend.refreshCalls += 1
                true
            },
        ).also { it.addListener(messages::add) }
    }

    private class FakeBackend(var enabled: Boolean) : HilSimulatorLifecycleCoordinator.Backend {
        var enableCalls = 0
        var disableCalls = 0
        var refreshCalls = 0
        private var enableCallback: ((Boolean, String) -> Unit)? = null
        private var disableCallback: ((Boolean, String) -> Unit)? = null

        override fun isEnabled(): Boolean = enabled

        override fun enable(
            origin: HilSimulatorLifecycleCoordinator.Origin,
            callback: (Boolean, String) -> Unit,
        ) {
            enableCalls += 1
            enableCallback = callback
        }

        override fun disable(callback: (Boolean, String) -> Unit) {
            disableCalls += 1
            disableCallback = callback
        }

        fun completeEnable(success: Boolean) {
            if (success) enabled = true
            enableCallback?.invoke(success, if (success) "ok" else "failed")
        }

        fun completeDisable(success: Boolean) {
            if (success) enabled = false
            disableCallback?.invoke(success, if (success) "ok" else "failed")
        }
    }

    private class FakeScheduler : HilSimulatorLifecycleCoordinator.Scheduler {
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            tasks += delayMillis to action
        }

        fun runNext(delayMillis: Long) {
            val index = tasks.indexOfFirst { it.first == delayMillis }
            check(index >= 0) { "No task scheduled for $delayMillis ms" }
            tasks.removeAt(index).second.invoke()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).second.invoke()
        }
    }

    private companion object {
        val ORIGIN = HilSimulatorLifecycleCoordinator.Origin(
            HilSimulatorLifecycleCoordinator.DEFAULT_LATITUDE,
            HilSimulatorLifecycleCoordinator.DEFAULT_LONGITUDE,
        )
        fun observation(active: Boolean, airborne: Boolean) =
            HilSimulatorLifecycleCoordinator.Observation(
                connected = true,
                simulatorReportedActive = active,
                simulatorAirborne = airborne,
            )
    }
}
