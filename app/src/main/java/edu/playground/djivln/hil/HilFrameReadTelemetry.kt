package edu.playground.djivln.hil

import android.os.SystemClock
import android.util.Log

/** Thread-confined rolling telemetry for locating socket stalls and consumer backpressure. */
internal class HilFrameReadTelemetry(private val role: String) {
    private var windowStartedNanos = 0L
    private var frames = 0L
    private var bytes = 0L
    private var longestReadNanos = 0L

    fun record(
        payloadBytes: Int,
        readNanos: Long,
        dispatcher: HilLatestFrameDispatcher.Snapshot,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        if (windowStartedNanos == 0L) windowStartedNanos = nowNanos
        frames += 1L
        bytes += payloadBytes.toLong()
        longestReadNanos = maxOf(longestReadNanos, readNanos)
        val elapsed = nowNanos - windowStartedNanos
        if (elapsed < REPORT_INTERVAL_NANOS) return
        val seconds = elapsed / 1_000_000_000.0
        Log.i(
            TAG,
            "role=$role readHz=${"%.1f".format(frames / seconds)} " +
                "throughputMiBps=${"%.1f".format(bytes / seconds / 1_048_576.0)} " +
                "maxReadMs=${longestReadNanos / 1_000_000L} " +
                "offered=${dispatcher.offered} dispatched=${dispatcher.dispatched} " +
                "coalesced=${dispatcher.droppedBeforeDispatch} " +
                "maxDispatchMs=${dispatcher.maximumDispatchMillis}",
        )
        windowStartedNanos = nowNanos
        frames = 0L
        bytes = 0L
        longestReadNanos = 0L
    }

    private companion object {
        const val TAG = "OpenFlyHilFrameStats"
        const val REPORT_INTERVAL_NANOS = 2_000_000_000L
    }
}
