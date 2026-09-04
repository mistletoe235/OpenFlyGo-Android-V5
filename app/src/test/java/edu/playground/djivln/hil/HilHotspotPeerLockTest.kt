package edu.playground.djivln.hil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HilHotspotPeerLockTest {
    @Test
    fun firstValidPeerWinsUntilTimeoutClearsDiscoveryCycle() {
        val lock = HilHotspotPeerLock()

        assertTrue(lock.accept("192.168.43.22"))
        assertTrue(lock.accept("192.168.43.22"))
        assertFalse(lock.accept("192.168.43.31"))

        lock.clear()

        assertTrue(lock.accept("192.168.43.31"))
        assertFalse(lock.accept("192.168.43.22"))
    }
}
