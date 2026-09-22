package edu.playground.djivln.survey

import org.junit.Assert.*
import org.junit.Test

class SurveyCameraReadbackCacheTest {
    @Test fun requestsAreBoundedAndExpiredValuesCannotAuthorizeFlight() {
        var now = 0L
        val cache = SurveyCameraReadbackCache { now }
        cache.changeSource("camera-a")
        var requests = 0
        var reply: ((Any?) -> Unit)? = null
        fun read() = cache.read("ratio") { requests++; reply = it }
        assertNull(read())
        reply?.invoke("4:3")
        assertEquals("4:3", read())
        assertEquals(1, requests)
        now = 1_000
        assertEquals("4:3", read())
        now = 2_001
        assertNull(read())
        reply?.invoke("16:9")
        assertEquals("16:9", read())
        now = 3_001
        read()
        reply?.invoke(null)
        assertNull(read())
    }

    @Test fun delayedReadbackAndPartialFailureCannotReplaceNewValues() {
        var now = 0L
        val cache = SurveyCameraReadbackCache { now }
        cache.changeSource("camera")
        var oldReply: ((Any?) -> Unit)? = null
        cache.read("ratio") { oldReply = it }
        now = 1_000
        assertEquals("16:9", cache.read("ratio") { it("16:9") })
        oldReply?.invoke("4:3")
        assertEquals("16:9", cache.read("ratio") { fail("too frequent") })
        assertNull(cache.read("zoom") { it(null) })
        assertEquals("16:9", cache.read("ratio") { fail("unrelated key") })
    }

    @Test fun disconnectAndOldCallbacksCannotRestorePreviousCameraValues() {
        var now = 0L
        val cache = SurveyCameraReadbackCache { now }
        var oldReply: ((Any?) -> Unit)? = null
        cache.changeSource("camera-a")
        cache.read("ratio") { oldReply = it }
        cache.changeSource(null)
        oldReply?.invoke("4:3")
        assertNull(cache.read("ratio") { fail("disconnected") })
        cache.changeSource("camera-b")
        assertNull(cache.read("ratio") { })
        oldReply?.invoke("4:3")
        assertNull(cache.read("ratio") { })
        now = 1_000
        assertEquals("16:9", cache.read("ratio") { it("16:9") })
    }
}
