package edu.playground.djivln.hil

/**
 * Per-authenticated-peer anti-replay window for unsigned OFHL sequence numbers.
 * UDP may reorder unique adjacent datagrams; duplicates and packets older than
 * the 64-packet window must not refresh the authenticated link.
 */
internal class HilPeerSequenceGuard {
    private var initialized = false
    private var last = 0L
    private var seenWindow = 0L

    @Synchronized
    fun accept(candidate: Long): Boolean {
        if (!initialized) {
            initialized = true
            last = candidate
            seenWindow = 1L
            return true
        }
        if (java.lang.Long.compareUnsigned(candidate, last) > 0) {
            val delta = candidate - last
            seenWindow = if (java.lang.Long.compareUnsigned(delta, WINDOW_BITS.toLong()) >= 0) {
                1L
            } else {
                (seenWindow shl delta.toInt()) or 1L
            }
            last = candidate
            return true
        }
        val delta = last - candidate
        if (java.lang.Long.compareUnsigned(delta, WINDOW_BITS.toLong()) >= 0) return false
        val mask = 1L shl delta.toInt()
        if (seenWindow and mask != 0L) return false
        seenWindow = seenWindow or mask
        return true
    }

    @Synchronized
    fun reset() {
        initialized = false
        last = 0L
        seenWindow = 0L
    }

    private companion object { const val WINDOW_BITS = 64 }
}
