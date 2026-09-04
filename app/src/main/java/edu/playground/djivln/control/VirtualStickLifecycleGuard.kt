package edu.playground.djivln.control

/**
 * Serializes asynchronous Virtual Stick enable/disable callbacks. DJI callbacks may arrive after
 * the operator has already released control, so a stale success must never re-arm the aircraft.
 */
class VirtualStickLifecycleGuard {
    class EnableToken internal constructor(internal val generation: Long)
    class DisableToken internal constructor(internal val generation: Long)

    enum class ObservationAction {
        NONE,
        ACCEPT_ENABLED,
        FORCE_DISABLE,
        CONFIRMED_DISABLED,
    }

    private var generation = 0L
    private var desiredEnabled = false
    private var managedSession = false
    private var enabledConfirmed = false
    private var disableCommandClaimed = false

    fun requestEnable(): EnableToken {
        generation += 1
        desiredEnabled = true
        managedSession = true
        enabledConfirmed = false
        disableCommandClaimed = false
        return EnableToken(generation)
    }

    fun requestDisable(): DisableToken {
        generation += 1
        desiredEnabled = false
        disableCommandClaimed = false
        return DisableToken(generation)
    }

    fun claimDisableCommand(): Boolean {
        if (!managedSession || desiredEnabled || disableCommandClaimed) return false
        disableCommandClaimed = true
        return true
    }

    fun releaseDisableCommandClaim() {
        disableCommandClaimed = false
    }

    fun accepts(token: EnableToken): Boolean =
        managedSession && desiredEnabled && token.generation == generation

    fun accepts(token: DisableToken): Boolean =
        managedSession && !desiredEnabled && token.generation == generation

    fun observeActual(enabled: Boolean): ObservationAction {
        if (enabled) {
            return when {
                managedSession && desiredEnabled && !enabledConfirmed -> {
                    enabledConfirmed = true
                    ObservationAction.ACCEPT_ENABLED
                }
                managedSession && desiredEnabled -> ObservationAction.NONE
                managedSession -> ObservationAction.FORCE_DISABLE
                else -> ObservationAction.NONE
            }
        }
        if (managedSession && !desiredEnabled) {
            managedSession = false
            enabledConfirmed = false
            disableCommandClaimed = false
            return ObservationAction.CONFIRMED_DISABLED
        }
        if (managedSession && desiredEnabled) {
            enabledConfirmed = false
        }
        return ObservationAction.NONE
    }

    fun wantsEnabled(): Boolean = desiredEnabled

    fun managesSession(): Boolean = managedSession
}
