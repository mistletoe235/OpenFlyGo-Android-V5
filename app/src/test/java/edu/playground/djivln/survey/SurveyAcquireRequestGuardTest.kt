package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyAcquireRequestGuardTest {
    @Test
    fun `cancel invalidates a late acquire callback`() {
        val guard = SurveyAcquireRequestGuard()
        val token = guard.begin(SurveyAcquireRequestGuard.Mode.RESUME)

        assertTrue(guard.pending)
        assertTrue(guard.resuming)
        assertTrue(guard.cancel())

        assertFalse(guard.pending)
        assertFalse(guard.resuming)
        assertFalse(guard.isCurrent(token))
        assertFalse(guard.consume(token))
    }

    @Test
    fun `new generation rejects an older callback`() {
        val guard = SurveyAcquireRequestGuard()
        val first = guard.begin(SurveyAcquireRequestGuard.Mode.START)
        val second = guard.begin(SurveyAcquireRequestGuard.Mode.RESUME)

        assertFalse(guard.isCurrent(first))
        assertFalse(guard.consume(first))
        assertTrue(guard.isCurrent(second))
        assertTrue(guard.consume(second))
        assertFalse(guard.pending)
    }
}
