package edu.playground.djivln.reconstruction

/**
 * Separates rolling preview artifacts from immutable final artifacts.
 * A preview may be regenerated several times under the same session and URL.
 */
object V86PointCloudCachePolicy {
    fun revision(snapshot: V86StreamingController.Snapshot): String {
        require(snapshot.sessionId != null) { "Point-cloud caching requires a cloud session" }
        return if (snapshot.completed) {
            "final"
        } else {
            "preview-${snapshot.imageCount.coerceAtLeast(0)}-" +
                "${snapshot.scal3rLaneCompletedWindows.coerceAtLeast(0)}"
        }
    }

    fun fileStem(snapshot: V86StreamingController.Snapshot): String {
        val sessionId = requireNotNull(snapshot.sessionId)
        require(sessionId.matches(Regex("[a-z0-9][a-z0-9_-]{5,63}"))) { "Invalid session ID" }
        return "$sessionId-${revision(snapshot)}"
    }
}
