package edu.playground.djivln.hil

/** Pure state machine so link-loss behavior can be unit-tested without Android networking. */
class HilLinkWatchdog(private val timeoutNanos: Long) {
    init {
        require(timeoutNanos > 0L)
    }

    private var everReceived = false
    private var lastReceiveNanos = 0L
    private var staleReported = false

    @Synchronized
    fun onReceive(nowNanos: Long) {
        everReceived = true
        lastReceiveNanos = nowNanos
        staleReported = false
    }

    @Synchronized
    fun isFresh(nowNanos: Long): Boolean {
        if (!everReceived) return false
        if (nowNanos <= lastReceiveNanos) return true
        return nowNanos - lastReceiveNanos <= timeoutNanos
    }

    /** Returns true once per fresh-to-stale transition. Initial connection establishment is not a loss. */
    @Synchronized
    fun pollStaleTransition(nowNanos: Long): Boolean {
        if (!everReceived || isFresh(nowNanos) || staleReported) return false
        staleReported = true
        return true
    }

    @Synchronized
    fun reset() {
        everReceived = false
        lastReceiveNanos = 0L
        staleReported = false
    }
}
