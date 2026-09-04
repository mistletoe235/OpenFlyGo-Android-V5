package edu.playground.djivln.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualStickPortLeaseTest {
    @Test fun requiresFreshActualStateBeforeGrantingControl() {
        val lease = VirtualStickPortLease()
        val owner = Any()
        lease.registerObserver()

        assertEquals(
            VirtualStickPortLease.AcquireBlock.STATE_NOT_OBSERVED,
            lease.tryAcquire(owner),
        )

        lease.observeActual(enabled = false, ownedByApp = false)
        assertNull(lease.tryAcquire(owner))
        assertTrue(lease.isHolder(owner))
    }

    @Test fun onlyOnePortCanHoldAndReleaseTheLease() {
        val lease = VirtualStickPortLease()
        val first = Any()
        val second = Any()
        lease.registerObserver()
        lease.observeActual(enabled = false, ownedByApp = false)

        assertNull(lease.tryAcquire(first))
        assertEquals(
            VirtualStickPortLease.AcquireBlock.ANOTHER_PORT_HOLDS_LEASE,
            lease.tryAcquire(second),
        )
        assertFalse(lease.release(second))
        assertTrue(lease.isHolder(first))
        assertTrue(lease.release(first))
        assertNull(lease.tryAcquire(second))
    }

    @Test fun externalVirtualStickSessionCannotBeClaimedByALocalPort() {
        val lease = VirtualStickPortLease()
        lease.registerObserver()
        lease.observeActual(enabled = true, ownedByApp = true)

        assertEquals(
            VirtualStickPortLease.AcquireBlock.EXTERNAL_SESSION_ACTIVE,
            lease.tryAcquire(Any()),
        )
        assertFalse(lease.snapshot().hasHolder)
    }

    @Test fun finalObserverRemovalInvalidatesCachedDisabledState() {
        val lease = VirtualStickPortLease()
        lease.registerObserver()
        lease.observeActual(enabled = false, ownedByApp = false)
        lease.unregisterObserver()

        assertFalse(lease.snapshot().stateObserved)

        lease.registerObserver()
        assertEquals(
            VirtualStickPortLease.AcquireBlock.STATE_NOT_OBSERVED,
            lease.tryAcquire(Any()),
        )
    }
}
