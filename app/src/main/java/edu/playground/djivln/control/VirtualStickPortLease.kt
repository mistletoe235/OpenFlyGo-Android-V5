package edu.playground.djivln.control

/**
 * Process-wide ownership for DJI's singleton VirtualStickManager.
 *
 * Each feature creates its own FlightControlPort, but all DJI ports ultimately address the same
 * aircraft control channel. This lease prevents one port from mistaking another port's MSDK
 * authority for its own and prevents an unrelated port from sending or disabling Virtual Stick.
 */
class VirtualStickPortLease {
    enum class AcquireBlock {
        STATE_NOT_OBSERVED,
        EXTERNAL_SESSION_ACTIVE,
        ANOTHER_PORT_HOLDS_LEASE,
        PORT_ALREADY_HOLDS_LEASE,
    }

    data class Snapshot(
        val stateObserved: Boolean,
        val actualEnabled: Boolean,
        val actualOwnedByApp: Boolean,
        val hasHolder: Boolean,
    )

    private var observerCount = 0
    private var stateObserved = false
    private var actualEnabled = false
    private var actualOwnedByApp = false
    private var holder: Any? = null

    @Synchronized
    fun registerObserver() {
        observerCount += 1
    }

    @Synchronized
    fun unregisterObserver() {
        observerCount = (observerCount - 1).coerceAtLeast(0)
        if (observerCount == 0) {
            // A cached "disabled" value is not enough to authorize a later session. Require a
            // fresh DJI state observation after listeners are installed again.
            stateObserved = false
            actualEnabled = false
            actualOwnedByApp = false
        }
    }

    @Synchronized
    fun observeActual(enabled: Boolean, ownedByApp: Boolean) {
        stateObserved = true
        actualEnabled = enabled
        actualOwnedByApp = enabled && ownedByApp
    }

    @Synchronized
    fun tryAcquire(owner: Any): AcquireBlock? {
        if (holder === owner) return AcquireBlock.PORT_ALREADY_HOLDS_LEASE
        if (holder != null) return AcquireBlock.ANOTHER_PORT_HOLDS_LEASE
        if (!stateObserved) return AcquireBlock.STATE_NOT_OBSERVED
        if (actualEnabled) return AcquireBlock.EXTERNAL_SESSION_ACTIVE
        holder = owner
        return null
    }

    @Synchronized
    fun isHolder(owner: Any): Boolean = holder === owner

    @Synchronized
    fun release(owner: Any): Boolean {
        if (holder !== owner) return false
        holder = null
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        stateObserved = stateObserved,
        actualEnabled = actualEnabled,
        actualOwnedByApp = actualOwnedByApp,
        hasHolder = holder != null,
    )
}

/** One lease for the one process-wide DJI VirtualStickManager channel. */
object ProcessVirtualStickPortLease {
    val instance = VirtualStickPortLease()
}
