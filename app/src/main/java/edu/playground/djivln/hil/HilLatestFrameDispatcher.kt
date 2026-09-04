package edu.playground.djivln.hil

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A single-slot handoff between the socket reader and frame consumers.
 *
 * The socket thread never waits for storage, preview, inference, or UI work. If a consumer is
 * still processing, a newly completed frame replaces the pending older frame. The frame already
 * being processed remains immutable; only the not-yet-dispatched slot is coalesced.
 */
internal class HilLatestFrameDispatcher<T>(
    threadName: String,
    private val consumer: (T) -> Unit,
) : AutoCloseable {
    data class Snapshot(
        val offered: Long,
        val dispatched: Long,
        val droppedBeforeDispatch: Long,
        val maximumDispatchMillis: Long,
    )

    private val running = AtomicBoolean(true)
    private val pending = AtomicReference<T?>()
    private val signal = Semaphore(0)
    private val offered = AtomicLong(0L)
    private val dispatched = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val maximumDispatchNanos = AtomicLong(0L)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    init {
        executor.execute(::dispatchLoop)
    }

    fun offer(value: T) {
        if (!running.get()) return
        offered.incrementAndGet()
        if (pending.getAndSet(value) != null) dropped.incrementAndGet()
        if (signal.availablePermits() == 0) signal.release()
    }

    fun snapshot(): Snapshot = Snapshot(
        offered = offered.get(),
        dispatched = dispatched.get(),
        droppedBeforeDispatch = dropped.get(),
        maximumDispatchMillis = maximumDispatchNanos.get() / 1_000_000L,
    )

    private fun dispatchLoop() {
        while (running.get()) {
            try {
                signal.acquire()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (!running.get()) break
            val value = pending.getAndSet(null) ?: continue
            val started = System.nanoTime()
            runCatching { consumer(value) }
                .onFailure { Log.e(TAG, "HIL frame consumer failed", it) }
            val elapsed = System.nanoTime() - started
            maximumDispatchNanos.accumulateAndGet(elapsed, ::maxOf)
            dispatched.incrementAndGet()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        pending.set(null)
        signal.release()
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "OpenFlyHilFrame"
    }
}
