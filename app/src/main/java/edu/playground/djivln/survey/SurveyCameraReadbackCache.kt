package edu.playground.djivln.survey

class SurveyCameraReadbackCache(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Sample(val value: Any, val receivedAt: Long)
    private var source: Any? = null
    private var generation = 0L
    private val samples = mutableMapOf<String, Sample>()
    private val requests = mutableMapOf<String, Long>()

    @Synchronized
    fun changeSource(identity: Any?) {
        if (source == identity) return
        source = identity
        generation++
        samples.clear()
        requests.clear()
    }

    @Synchronized
    fun read(key: String, request: ((Any?) -> Unit) -> Unit): Any? {
        if (source == null) return null
        val now = nowMillis()
        if (requests[key]?.let { now - it >= 1_000 } != false) {
            val currentGeneration = generation
            requests[key] = now
            val completion: (Any?) -> Unit = { value ->
                synchronized(this) {
                    if (generation == currentGeneration && requests[key] == now) {
                        if (value == null) samples.remove(key)
                        else samples[key] = Sample(value, nowMillis())
                    }
                }
            }
            try { request(completion) } catch (_: Exception) { completion(null) }
        }
        return samples[key]?.takeIf { now - it.receivedAt in 0..2_000 }?.value
    }
}
