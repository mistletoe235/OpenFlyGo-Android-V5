package edu.playground.djivln.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppChromePolicyTest {
    @Test
    fun `normal cockpit keeps requested chrome visible`() {
        val visibility = AppChromePolicy.resolve(
            mapFullscreen = false,
            surveyVisible = false,
            vlnRequested = true,
        )
        assertTrue(visibility.topStatusVisible)
        assertTrue(visibility.vlnVisible)
    }

    @Test
    fun `fullscreen map hides top bar and vln`() {
        val visibility = AppChromePolicy.resolve(
            mapFullscreen = true,
            surveyVisible = false,
            vlnRequested = true,
        )
        assertFalse(visibility.topStatusVisible)
        assertFalse(visibility.vlnVisible)
    }

    @Test
    fun `survey hides top bar and vln`() {
        val visibility = AppChromePolicy.resolve(
            mapFullscreen = false,
            surveyVisible = true,
            vlnRequested = true,
        )
        assertFalse(visibility.topStatusVisible)
        assertFalse(visibility.vlnVisible)
    }
}
