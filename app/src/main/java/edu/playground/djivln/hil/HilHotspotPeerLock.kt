package edu.playground.djivln.hil

import java.util.concurrent.atomic.AtomicReference

/** Locks one discovery cycle to the first UE peer that proves the session ID. */
internal class HilHotspotPeerLock {
    private val peer = AtomicReference<String?>(null)

    fun accept(candidateHost: String): Boolean {
        while (true) {
            val current = peer.get()
            if (current != null) return current == candidateHost
            if (peer.compareAndSet(null, candidateHost)) return true
        }
    }

    fun clear() {
        peer.set(null)
    }
}
