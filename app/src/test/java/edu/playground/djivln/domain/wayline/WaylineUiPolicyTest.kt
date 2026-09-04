package edu.playground.djivln.domain.wayline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaylineUiPolicyTest {
    @Test fun disconnectedAircraftCannotRunMission() {
        val actions = WaylineUiPolicy.actions(false, true, WaylineState(WaylinePhase.READY))
        assertFalse(actions.canUpload)
        assertFalse(actions.canExecute)
    }

    @Test fun disconnectedWaylineServiceCannotUploadOrExecute() {
        val actions = WaylineUiPolicy.actions(true, true, WaylineState(WaylinePhase.DISCONNECTED))
        assertFalse(actions.canUpload)
        assertFalse(actions.canExecute)
        assertFalse(actions.canPause)
        assertFalse(actions.canResume)
        assertFalse(actions.canStop)
        assertTrue(WaylineUiPolicy.unavailableReason(true, WaylineState(WaylinePhase.DISCONNECTED)) != null)
    }

    @Test fun unsupportedAircraftCannotUploadOrExecute() {
        val actions = WaylineUiPolicy.actions(true, true, WaylineState(WaylinePhase.UNSUPPORTED))
        assertFalse(actions.canUpload)
        assertFalse(actions.canExecute)
        assertTrue(WaylineUiPolicy.unavailableReason(true, WaylineState(WaylinePhase.UNSUPPORTED)) != null)
    }

    @Test fun uploadedMissionCanExecute() {
        val actions = WaylineUiPolicy.actions(true, true, WaylineState(WaylinePhase.READY))
        assertTrue(actions.canExecute)
        assertTrue(actions.canUpload)
    }

    @Test fun executingMissionCanPauseAndStopOnly() {
        val actions = WaylineUiPolicy.actions(true, true, WaylineState(WaylinePhase.EXECUTING))
        assertTrue(actions.canPause)
        assertTrue(actions.canStop)
        assertFalse(actions.canExecute)
        assertFalse(actions.canResume)
        assertFalse(actions.canUpload)
    }

    @Test fun interruptedMissionCanResumeOrStop() {
        val actions = WaylineUiPolicy.actions(true, true, WaylineState(WaylinePhase.PAUSED))
        assertTrue(actions.canResume)
        assertTrue(actions.canStop)
        assertFalse(actions.canPause)
    }
}
