package edu.playground.djivln.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftStatusPageLifecycleTest {
    @Test
    fun `reentrant telemetry render stays blocked until build completes`() {
        val lifecycle = AircraftStatusPageLifecycle()

        lifecycle.beginBuild()

        assertEquals(AircraftStatusPageLifecycle.State.BUILDING, lifecycle.state)
        assertFalse(lifecycle.canRender)

        lifecycle.completeBuild()
        assertTrue(lifecycle.canRender)
    }

    @Test
    fun `failed build returns to retryable idle state`() {
        val lifecycle = AircraftStatusPageLifecycle()
        lifecycle.beginBuild()

        lifecycle.failBuild()

        assertEquals(AircraftStatusPageLifecycle.State.IDLE, lifecycle.state)
        assertFalse(lifecycle.canRender)
        lifecycle.beginBuild()
        lifecycle.completeBuild()
        assertTrue(lifecycle.canRender)
    }
}
