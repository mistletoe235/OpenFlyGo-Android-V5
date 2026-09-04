package edu.playground.djivln.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSimulationInterlockTest {
    @Test fun requiresExplicitConfirmationAndRevokesAfterRelease() {
        val interlock = ExternalSimulationInterlock()
        assertFalse(interlock.isConfirmed())

        interlock.confirmForCurrentSession()
        assertTrue(interlock.isConfirmed())

        interlock.revoke()
        assertFalse(interlock.isConfirmed())
    }
}
