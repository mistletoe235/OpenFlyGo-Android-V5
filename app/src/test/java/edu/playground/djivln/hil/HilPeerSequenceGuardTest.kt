package edu.playground.djivln.hil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HilPeerSequenceGuardTest {
    @Test fun acceptsUniqueReorderingButRejectsDuplicateAndOldPacketsUntilPeerReset() {
        val guard = HilPeerSequenceGuard()
        assertTrue(guard.accept(10L))
        assertFalse(guard.accept(10L))
        assertTrue(guard.accept(12L))
        assertTrue(guard.accept(11L))
        assertFalse(guard.accept(11L))
        assertTrue(guard.accept(80L))
        assertFalse(guard.accept(16L))
        guard.reset()
        assertTrue(guard.accept(1L))
    }

    @Test fun comparesSequenceAsUnsigned() {
        val guard = HilPeerSequenceGuard()
        assertTrue(guard.accept(Long.MAX_VALUE))
        assertTrue(guard.accept(Long.MIN_VALUE))
        assertFalse(guard.accept(Long.MAX_VALUE))
    }
}
