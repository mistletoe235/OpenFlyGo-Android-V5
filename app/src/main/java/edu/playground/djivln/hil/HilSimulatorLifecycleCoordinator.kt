package edu.playground.djivln.hil

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

class HilSimulatorLifecycleCoordinator(
    private val backend: Backend,
    private val scheduler: Scheduler,
    private val rawFresh: () -> Boolean,
    private val refreshRawListener: () -> Boolean,
) : AutoCloseable {
    data class Origin(val latitude: Double, val longitude: Double)
    data class Observation(
        val connected: Boolean,
        val simulatorReportedActive: Boolean,
        val simulatorAirborne: Boolean,
    )

    fun interface Scheduler {
        fun postDelayed(delayMillis: Long, action: () -> Unit)
    }

    interface Backend {
        fun isEnabled(): Boolean
        fun enable(origin: Origin, callback: (Boolean, String) -> Unit)
        fun disable(callback: (Boolean, String) -> Unit)
    }

    fun interface Listener {
        fun onStateChanged(message: UiText)
    }

    private val listeners = linkedSetOf<Listener>()
    private var generation = 0L
    private var running = false
    private var origin = Origin(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
    private var observation = Observation(false, false, false)
    private var operationInFlight = false
    private var retryScheduled = false
    private var enableAttempts = 0
    private var rawRefreshAttempts = 0
    private var cleanStartRequired = true
    private var manuallyDisabled = false
    private var message = UiText.resource(R.string.simulator_not_checked)

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStateChanged(message)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @Synchronized
    fun start(origin: Origin, observation: Observation) {
        generation += 1L
        running = true
        this.origin = origin
        this.observation = observation
        operationInFlight = false
        retryScheduled = false
        enableAttempts = 0
        rawRefreshAttempts = 0
        manuallyDisabled = false
        publish(R.string.simulator_start_checking)
        reconcile(generation)
    }

    @Synchronized
    fun observe(origin: Origin, observation: Observation) {
        this.origin = origin
        this.observation = observation
        if (running) reconcile(generation)
    }

    @Synchronized
    fun stopPreservingSimulator() {
        generation += 1L
        running = false
        operationInFlight = false
        retryScheduled = false
        publish(R.string.simulator_state_preserved)
    }

    @Synchronized
    fun setManualEnabled(
        enabled: Boolean,
        origin: Origin,
        observation: Observation,
        callback: (Boolean, String) -> Unit,
    ) {
        generation += 1L
        val expectedGeneration = generation
        this.origin = origin
        this.observation = observation
        operationInFlight = true
        retryScheduled = false
        enableAttempts = 0
        rawRefreshAttempts = 0
        manuallyDisabled = !enabled
        publish(if (enabled) R.string.simulator_manual_starting else R.string.simulator_manual_stopping)
        val completion: (Boolean, String) -> Unit = { success, detail ->
            synchronized(this) {
                if (expectedGeneration != generation) return@synchronized
                operationInFlight = false
                val effectiveSuccess = if (enabled) success || backend.isEnabled() else success || !backend.isEnabled()
                if (enabled && effectiveSuccess) {
                    manuallyDisabled = false
                    cleanStartRequired = false
                    publish(R.string.simulator_manual_start_succeeded)
                    if (running) refreshStaleRaw(expectedGeneration)
                } else if (!enabled && effectiveSuccess) {
                    manuallyDisabled = true
                    cleanStartRequired = true
                    publish(R.string.simulator_manual_closed)
                } else {
                    manuallyDisabled = false
                    publish(
                        R.string.simulator_manual_operation_failed,
                        UiText.resource(if (enabled) R.string.action_start else R.string.action_stop),
                        UiText.external(detail),
                    )
                    if (running) reconcile(expectedGeneration)
                }
                callback(effectiveSuccess, detail)
            }
        }
        if (enabled) backend.enable(origin, completion) else backend.disable(completion)
    }

    @Synchronized
    fun currentMessage(): UiText = message

    @Synchronized
    private fun reconcile(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration) || operationInFlight || retryScheduled) return
        if (manuallyDisabled) {
            if ((backend.isEnabled() || observation.simulatorReportedActive) && !observation.simulatorAirborne) {
                enforceManualDisable(expectedGeneration)
                return
            }
            publish(R.string.simulator_manual_closed)
            return
        }
        val enabled = backend.isEnabled() || observation.simulatorReportedActive
        if (rawFresh()) {
            rawRefreshAttempts = 0
            if (observation.simulatorAirborne) {
                publish(R.string.simulator_airborne_adopted)
                return
            }
            if (enabled && cleanStartRequired) {
                beginGroundedCleanStart(expectedGeneration, 1)
                return
            }
            publish(R.string.simulator_raw_ready)
            return
        }
        if (!observation.connected) {
            publish(R.string.simulator_waiting_for_aircraft)
            return
        }
        if (observation.simulatorAirborne && !enabled) {
            publish(R.string.simulator_non_simulator_airborne)
            return
        }
        if (enabled) {
            refreshStaleRaw(expectedGeneration)
            return
        }
        beginEnable(expectedGeneration)
    }

    private fun enforceManualDisable(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration) || operationInFlight) return
        operationInFlight = true
        publish(R.string.simulator_restore_manual_disable)
        backend.disable { success, detail ->
            synchronized(this) {
                if (!isCurrent(expectedGeneration)) return@synchronized
                operationInFlight = false
                publish(
                    if (success || !backend.isEnabled()) {
                        UiText.resource(R.string.simulator_manual_closed)
                    } else {
                        UiText.resource(R.string.simulator_manual_close_hold_failed, UiText.external(detail))
                    },
                )
            }
        }
    }

    private fun beginGroundedCleanStart(expectedGeneration: Long, attempt: Int) {
        if (!isCurrent(expectedGeneration) || operationInFlight) return
        operationInFlight = true
        publish(R.string.simulator_ground_residual_clean_start, attempt, MAX_DISABLE_ATTEMPTS)
        var completed = false
        backend.disable { success, detail ->
            synchronized(this) {
                if (completed || !isCurrent(expectedGeneration)) return@synchronized
                completed = true
                operationInFlight = false
                if (success || !backend.isEnabled()) {
                    scheduler.postDelayed(CLEAN_START_SETTLE_MILLIS) {
                        synchronized(this) {
                            if (isCurrent(expectedGeneration)) beginEnable(expectedGeneration)
                        }
                    }
                } else if (attempt < MAX_DISABLE_ATTEMPTS && !observation.simulatorAirborne) {
                    scheduler.postDelayed(RETRY_DELAY_MILLIS) {
                        synchronized(this) {
                            if (isCurrent(expectedGeneration)) beginGroundedCleanStart(expectedGeneration, attempt + 1)
                        }
                    }
                } else {
                    publish(R.string.simulator_clean_start_failed, UiText.external(detail))
                }
            }
        }
        scheduler.postDelayed(OPERATION_TIMEOUT_MILLIS) {
            synchronized(this) {
                if (completed || !isCurrent(expectedGeneration)) return@synchronized
                completed = true
                operationInFlight = false
                if (!backend.isEnabled()) {
                    scheduler.postDelayed(CLEAN_START_SETTLE_MILLIS) {
                        synchronized(this) {
                            if (isCurrent(expectedGeneration)) beginEnable(expectedGeneration)
                        }
                    }
                } else if (attempt < MAX_DISABLE_ATTEMPTS && !observation.simulatorAirborne) {
                    beginGroundedCleanStart(expectedGeneration, attempt + 1)
                } else {
                    publish(R.string.simulator_ground_stop_timeout)
                }
            }
        }
    }

    private fun beginEnable(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration) || operationInFlight) return
        if (enableAttempts >= MAX_ENABLE_ATTEMPTS) {
            publish(R.string.simulator_start_exhausted, enableAttempts)
            return
        }
        operationInFlight = true
        enableAttempts += 1
        val attempt = enableAttempts
        publish(R.string.simulator_auto_start_attempt, attempt, MAX_ENABLE_ATTEMPTS)
        var completed = false
        backend.enable(origin) { success, detail ->
            synchronized(this) {
                if (completed || !isCurrent(expectedGeneration)) return@synchronized
                completed = true
                operationInFlight = false
                if (success || backend.isEnabled()) {
                    cleanStartRequired = false
                    refreshStaleRaw(expectedGeneration)
                } else if (attempt < MAX_ENABLE_ATTEMPTS) {
                    publish(R.string.simulator_start_failed_retrying, UiText.external(detail))
                    retryScheduled = true
                    scheduler.postDelayed(RETRY_DELAY_MILLIS) {
                        synchronized(this) {
                            retryScheduled = false
                            if (isCurrent(expectedGeneration)) beginEnable(expectedGeneration)
                        }
                    }
                } else {
                    publish(R.string.simulator_unavailable, UiText.external(detail))
                }
            }
        }
        scheduler.postDelayed(OPERATION_TIMEOUT_MILLIS) {
            synchronized(this) {
                if (completed || !isCurrent(expectedGeneration)) return@synchronized
                completed = true
                operationInFlight = false
                if (backend.isEnabled() || rawFresh()) {
                    cleanStartRequired = false
                    publish(R.string.simulator_callback_timeout_sdk_active)
                    refreshStaleRaw(expectedGeneration)
                } else if (attempt < MAX_ENABLE_ATTEMPTS) {
                    retryScheduled = true
                    scheduler.postDelayed(RETRY_DELAY_MILLIS) {
                        synchronized(this) {
                            retryScheduled = false
                            if (isCurrent(expectedGeneration)) beginEnable(expectedGeneration)
                        }
                    }
                } else {
                    publish(R.string.simulator_start_timeout)
                }
            }
        }
    }

    private fun refreshStaleRaw(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration)) return
        if (rawFresh()) {
            rawRefreshAttempts = 0
            publish(R.string.simulator_raw_ready)
            return
        }
        if (rawRefreshAttempts >= MAX_RAW_REFRESH_ATTEMPTS) {
            publish(R.string.simulator_raw_timeout)
            return
        }
        rawRefreshAttempts += 1
        val rebound = refreshRawListener()
        publish(R.string.simulator_refreshing_raw, rawRefreshAttempts, MAX_RAW_REFRESH_ATTEMPTS, rebound)
        scheduler.postDelayed(RAW_CHECK_MILLIS) {
            synchronized(this) { if (isCurrent(expectedGeneration)) refreshStaleRaw(expectedGeneration) }
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean = running && generation == expectedGeneration

    private fun publish(resourceId: Int, vararg arguments: Any?) = publish(UiText.resource(resourceId, *arguments))

    private fun publish(value: UiText) {
        message = value
        listeners.toList().forEach { it.onStateChanged(value) }
    }

    override fun close() {
        stopPreservingSimulator()
        listeners.clear()
    }

    companion object {
        const val DEFAULT_LATITUDE = 31.1815535
        const val DEFAULT_LONGITUDE = 121.4736515
        const val MAX_ENABLE_ATTEMPTS = 20
        const val MAX_DISABLE_ATTEMPTS = 3
        const val MAX_RAW_REFRESH_ATTEMPTS = 5
        const val RETRY_DELAY_MILLIS = 1_500L
        const val CLEAN_START_SETTLE_MILLIS = 1_500L
        const val OPERATION_TIMEOUT_MILLIS = 6_000L
        const val RAW_CHECK_MILLIS = 500L
    }
}
