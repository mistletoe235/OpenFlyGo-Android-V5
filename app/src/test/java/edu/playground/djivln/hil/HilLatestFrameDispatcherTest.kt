package edu.playground.djivln.hil

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HilLatestFrameDispatcherTest {
    @Test fun slowConsumerKeepsOnlyNewestPendingFrame() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val twoDelivered = CountDownLatch(2)
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = HilLatestFrameDispatcher<Int>("hil-dispatch-test") { value ->
            delivered += value
            if (value == 1) {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            twoDelivered.countDown()
        }
        try {
            dispatcher.offer(1)
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            dispatcher.offer(2)
            dispatcher.offer(3)
            releaseFirst.countDown()
            assertTrue(twoDelivered.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), delivered.toList())
            assertEquals(1L, dispatcher.snapshot().droppedBeforeDispatch)
        } finally {
            dispatcher.close()
        }
    }
}
