package edu.playground.djivln.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualStickLifecycleGuardTest {
    @Test fun currentEnableCanBeAccepted() {
        val guard = VirtualStickLifecycleGuard()
        val token = guard.requestEnable()

        assertTrue(guard.accepts(token))
        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.ACCEPT_ENABLED,
            guard.observeActual(enabled = true),
        )
    }

    @Test fun duplicateEnabledObservationsAreIgnoredAfterFirstConfirmation() {
        val guard = VirtualStickLifecycleGuard()
        guard.requestEnable()

        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.ACCEPT_ENABLED,
            guard.observeActual(enabled = true),
        )
        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.NONE,
            guard.observeActual(enabled = true),
        )
    }

    @Test fun confirmedDisableInvalidatesItsLateCallback() {
        val guard = VirtualStickLifecycleGuard()
        guard.requestEnable()
        guard.observeActual(enabled = true)
        val disable = guard.requestDisable()

        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.CONFIRMED_DISABLED,
            guard.observeActual(enabled = false),
        )
        assertFalse(guard.accepts(disable))
    }

    @Test fun stopInvalidatesLateEnableSuccessAndForcesDisable() {
        val guard = VirtualStickLifecycleGuard()
        val staleToken = guard.requestEnable()
        guard.requestDisable()

        assertFalse(guard.accepts(staleToken))
        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.FORCE_DISABLE,
            guard.observeActual(enabled = true),
        )
        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.CONFIRMED_DISABLED,
            guard.observeActual(enabled = false),
        )
        assertFalse(guard.managesSession())
    }

    @Test fun synchronousEnabledCallbackCannotRecursivelyIssueDisable() {
        val guard = VirtualStickLifecycleGuard()
        guard.requestEnable()
        guard.observeActual(enabled = true)
        guard.requestDisable()

        assertTrue(guard.claimDisableCommand())
        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.FORCE_DISABLE,
            guard.observeActual(enabled = true),
        )
        assertFalse(guard.claimDisableCommand())
    }

    @Test fun failedDisableCanBeRetried() {
        val guard = VirtualStickLifecycleGuard()
        guard.requestEnable()
        guard.observeActual(enabled = true)
        guard.requestDisable()

        assertTrue(guard.claimDisableCommand())
        guard.releaseDisableCommandClaim()
        assertTrue(guard.claimDisableCommand())
    }

    @Test fun doesNotDisableAuthorityItNeverRequested() {
        val guard = VirtualStickLifecycleGuard()

        assertEquals(
            VirtualStickLifecycleGuard.ObservationAction.NONE,
            guard.observeActual(enabled = true),
        )
    }

    @Test fun newerEnableInvalidatesOlderCallback() {
        val guard = VirtualStickLifecycleGuard()
        val old = guard.requestEnable()
        val current = guard.requestEnable()

        assertFalse(guard.accepts(old))
        assertTrue(guard.accepts(current))
    }

    @Test fun newerEnableInvalidatesPendingDisableCallback() {
        val guard = VirtualStickLifecycleGuard()
        guard.requestEnable()
        val staleDisable = guard.requestDisable()
        val currentEnable = guard.requestEnable()

        assertFalse(guard.accepts(staleDisable))
        assertTrue(guard.accepts(currentEnable))
    }
}
