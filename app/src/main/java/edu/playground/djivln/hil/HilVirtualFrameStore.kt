package edu.playground.djivln.hil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HilVirtualFrameStore {
    data class Snapshot(
        val frameId: Long,
        val poseSequence: Long,
        val ageMillis: Long,
        val width: Int,
        val height: Int,
        val receivedFrames: Long,
        val measuredReceiveHz: Double,
        val rejectedFrames: Long,
    )

    private val latest = AtomicReference<HilFrameProtocol.Frame?>()
    private val receivedFrames = AtomicLong(0L)
    private val rejectedFrames = AtomicLong(0L)
    private var rateWindowStartedNanos = 0L
    private var rateWindowFrames = 0L
    @Volatile private var measuredReceiveHz = 0.0

    @Synchronized fun offer(frame: HilFrameProtocol.Frame) {
        val previous = latest.get()
        if (previous != null && frame.frameId <= previous.frameId) {
            rejectedFrames.incrementAndGet()
            return
        }
        latest.set(frame)
        receivedFrames.incrementAndGet()
        val now = frame.receivedAndroidMonotonicNanos
        if (rateWindowStartedNanos == 0L) rateWindowStartedNanos = now
        rateWindowFrames += 1L
        val windowNanos = now - rateWindowStartedNanos
        if (windowNanos >= 1_000_000_000L) {
            measuredReceiveHz = rateWindowFrames * 1_000_000_000.0 / windowNanos
            rateWindowFrames = 0L
            rateWindowStartedNanos = now
        }
    }

    fun snapshot(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Snapshot? {
        val frame = latest.get() ?: return null
        return Snapshot(
            frame.frameId,
            frame.poseSequence,
            ((nowNanos - frame.receivedAndroidMonotonicNanos).coerceAtLeast(0L)) / 1_000_000L,
            frame.width,
            frame.height,
            receivedFrames.get(),
            measuredReceiveHz,
            rejectedFrames.get(),
        )
    }

    fun decodeLatest(maxAgeMillis: Long, nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Bitmap? {
        val frame = latest.get() ?: return null
        val ageMillis = (nowNanos - frame.receivedAndroidMonotonicNanos).coerceAtLeast(0L) / 1_000_000L
        if (ageMillis > maxAgeMillis) return null
        return BitmapFactory.decodeByteArray(frame.encoded, 0, frame.encoded.size)
    }

    fun latestFrame(maxAgeMillis: Long, nowNanos: Long = SystemClock.elapsedRealtimeNanos()): HilFrameProtocol.Frame? {
        val frame = latest.get() ?: return null
        val ageMillis = (nowNanos - frame.receivedAndroidMonotonicNanos).coerceAtLeast(0L) / 1_000_000L
        if (ageMillis > maxAgeMillis) return null
        return frame.copy(encoded = frame.encoded.copyOf())
    }

    /**
     * Returns the store-owned immutable frame reference only when it is newer than the caller's cursor.
     * Consumers must treat [HilFrameProtocol.Frame.encoded] as read-only.
     */
    internal fun borrowLatestFrameIfNew(
        maxAgeMillis: Long,
        lastStreamGeneration: Long,
        lastFrameId: Long,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): HilFrameProtocol.Frame? {
        val frame = latest.get() ?: return null
        val ageMillis = (nowNanos - frame.receivedAndroidMonotonicNanos).coerceAtLeast(0L) / 1_000_000L
        if (ageMillis > maxAgeMillis) return null
        if (frame.streamGeneration == lastStreamGeneration && frame.frameId <= lastFrameId) return null
        return frame
    }

    @Synchronized fun clear() {
        latest.set(null)
        receivedFrames.set(0L)
        rejectedFrames.set(0L)
        rateWindowStartedNanos = 0L
        rateWindowFrames = 0L
        measuredReceiveHz = 0.0
    }
}
