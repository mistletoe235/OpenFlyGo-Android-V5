package edu.playground.djivln.ui

internal class LatestMapUpdate<Value>(
    private val clockMillis: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val render: (Value) -> Unit,
) {
    private data class Pending<Value>(val value: Value)

    private var pending: Pending<Value>? = null
    private var queued = false
    private var lastRenderMillis: Long? = null
    private val refresh = Runnable {
        queued = false
        val next = pending
        pending = null
        if (next != null) {
            lastRenderMillis = clockMillis()
            render(next.value)
        }
    }

    fun submit(value: Value) {
        pending = Pending(value)
        if (queued) return
        queued = true
        val delay = lastRenderMillis?.let { (200L - (clockMillis() - it)).coerceAtLeast(0L) } ?: 0L
        schedule(refresh, delay)
    }

    fun clear() {
        cancel(refresh)
        pending = null
        queued = false
        lastRenderMillis = null
    }
}
