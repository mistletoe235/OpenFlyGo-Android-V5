package edu.playground.djivln.hil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HilLinkWatchdogTest {
    @Test
    fun reportsOnlyOneTransitionUntilLinkRecovers() {
        val watchdog = HilLinkWatchdog(100L)
        assertFalse(watchdog.pollStaleTransition(500L))
        watchdog.onReceive(1_000L)
        assertTrue(watchdog.isFresh(1_050L))
        assertFalse(watchdog.pollStaleTransition(1_050L))
        assertTrue(watchdog.pollStaleTransition(1_101L))
        assertFalse(watchdog.pollStaleTransition(1_200L))
        watchdog.onReceive(1_300L)
        assertTrue(watchdog.pollStaleTransition(1_401L))
    }

    @Test
    fun olderPollTimestampDoesNotInvalidateNewerReceive() {
        val watchdog = HilLinkWatchdog(100L)
        watchdog.onReceive(1_100L)
        assertTrue(watchdog.isFresh(1_050L))
        assertFalse(watchdog.pollStaleTransition(1_050L))
    }
}
