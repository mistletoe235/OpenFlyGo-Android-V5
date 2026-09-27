package edu.playground.djivln.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualStickDisableCompletionTest {
    @Test fun disabledObservationDoesNotReleaseBeforeSdkCommandCompletion() {
        val completion = VirtualStickDisableCompletion()
        val token = completion.begin()
        completion.observeEnabled(false)
        assertTrue(completion.commandPending)
        assertFalse(completion.ready)
        assertTrue(completion.completeCommand(token))
        assertTrue(completion.ready)
    }

    @Test fun sdkSuccessDoesNotReleaseBeforeDisabledObservation() {
        val completion = VirtualStickDisableCompletion()
        val token = completion.begin()
        completion.completeCommand(token)
        assertFalse(completion.commandPending)
        assertFalse(completion.ready)
        completion.observeEnabled(false)
        assertTrue(completion.ready)
    }

    @Test fun oldCallbackCannotCompleteNewRelease() {
        val completion = VirtualStickDisableCompletion()
        val old = completion.begin()
        completion.observeEnabled(false)
        completion.completeCommand(old)
        completion.clear()
        val current = completion.begin()
        completion.observeEnabled(false)
        assertFalse(completion.completeCommand(old))
        assertFalse(completion.ready)
        completion.completeCommand(current)
        assertTrue(completion.ready)
    }

    @Test fun oldCallbackAfterReacquisitionDoesNothing() {
        val completion = VirtualStickDisableCompletion()
        val old = completion.begin()
        completion.observeEnabled(false)
        completion.completeCommand(old)
        completion.clear()
        completion.observeEnabled(true)
        assertFalse(completion.completeCommand(old))
        assertFalse(completion.ready)
    }

    @Test fun renewedEnabledStateInvalidatesEarlierDisabledObservation() {
        val completion = VirtualStickDisableCompletion()
        val token = completion.begin()
        completion.observeEnabled(false)
        completion.observeEnabled(true)
        completion.completeCommand(token)
        assertFalse(completion.ready)
        completion.observeEnabled(false)
        assertTrue(completion.ready)
    }

    @Test(expected = IllegalStateException::class)
    fun anotherDisableCannotOverlapUnfinishedSdkCommand() {
        val completion = VirtualStickDisableCompletion()
        completion.begin()
        completion.observeEnabled(false)
        completion.begin()
    }

    @Test fun handoffKeepsLeaseUntilBothConditionsHold() {
        val lease = VirtualStickPortLease()
        val completion = VirtualStickDisableCompletion()
        val first = Any()
        val next = Any()
        lease.observeActual(false, false)
        assertTrue(lease.tryAcquire(first) == null)
        val token = completion.begin()
        lease.observeActual(false, false)
        completion.observeEnabled(false)
        if (completion.ready) lease.release(first)
        assertFalse(lease.tryAcquire(next) == null)
        completion.completeCommand(token)
        if (completion.ready) {
            completion.clear()
            lease.release(first)
        }
        assertTrue(lease.tryAcquire(next) == null)
        assertFalse(completion.completeCommand(token))
        assertTrue(lease.isHolder(next))
    }
}
