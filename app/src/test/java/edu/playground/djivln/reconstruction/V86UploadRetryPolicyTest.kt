package edu.playground.djivln.reconstruction

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V86UploadRetryPolicyTest {
    @Test fun transientNetworkAndServerFailuresRetryAutomatically() {
        assertTrue(V86UploadRetryPolicy.isAutomaticallyRetryable(SocketTimeoutException()))
        assertTrue(V86UploadRetryPolicy.isAutomaticallyRetryable(V86HttpException(408, "timeout")))
        assertTrue(V86UploadRetryPolicy.isAutomaticallyRetryable(V86HttpException(429, "busy")))
        assertTrue(V86UploadRetryPolicy.isAutomaticallyRetryable(V86HttpException(503, "down")))
    }

    @Test fun deterministicClientAndAuthenticationFailuresWaitForUserCorrection() {
        assertFalse(V86UploadRetryPolicy.isAutomaticallyRetryable(V86HttpException(400, "bad image")))
        assertFalse(V86UploadRetryPolicy.isAutomaticallyRetryable(V86HttpException(401, "token")))
        assertFalse(V86UploadRetryPolicy.isAutomaticallyRetryable(V86HttpException(403, "forbidden")))
    }

    @Test fun exponentialBackoffIsBoundedAtOneMinute() {
        assertEquals(2_000L, V86UploadRetryPolicy.delayMillis(1))
        assertEquals(4_000L, V86UploadRetryPolicy.delayMillis(2))
        assertEquals(60_000L, V86UploadRetryPolicy.delayMillis(6))
        assertEquals(60_000L, V86UploadRetryPolicy.delayMillis(100))
    }
}
