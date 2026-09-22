package edu.playground.djivln.reconstruction

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class V86RemoteSessionClient(endpoint: String, val sessionId: String, private val token: String) : AutoCloseable {
    val endpoint = V86HttpClient.normalizeEndpoint(endpoint)
    private val origin = URL(this.endpoint)
    @Volatile private var closed = false
    @Volatile private var active: HttpURLConnection? = null

    init {
        require(sessionId.matches(Regex("[a-z0-9][a-z0-9_-]{5,63}"))) { "Invalid session ID" }
        require(token.none { it.code < 32 || it.code == 127 }) { "Invalid access code" }
    }

    fun result(): V86Result {
        val json = JSONObject(text("/api/sessions/$sessionId/result", 1024 * 1024))
        require(json.getString("session_id") == sessionId) { "Server returned a different session" }
        return V86Result.decode(json)
    }

    fun mission(result: V86Result): String {
        require(result.sessionId == sessionId) { "Session changed; refresh first" }
        require(!result.relativeHeightTest) { "Relative-height tests cannot provide flight missions" }
        return text(requireNotNull(result.missionUrl) { "Mission is not ready" }, 8 * 1024 * 1024)
    }

    fun pointCloud(result: V86Result, target: File): File {
        require(result.sessionId == sessionId) { "Session changed; refresh first" }
        val path = requireNotNull(result.pointCloudUrl) { "Point cloud is not ready" }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".part")
        try {
            temporary.outputStream().buffered().use { download(path, 64L * 1024 * 1024, it) }
            check(!closed) { "Request cancelled" }
            check(temporary.renameTo(target)) { "Cannot store point cloud" }
            return target
        } finally { temporary.delete() }
    }

    private fun text(path: String, limit: Int): String = ByteArrayOutputStream().use { output ->
        download(path, limit.toLong(), output)
        output.toString(Charsets.UTF_8.name())
    }

    private fun download(path: String, limit: Long, output: OutputStream) {
        check(!closed) { "Request cancelled" }
        val url = URL(origin, path)
        require(url.userInfo == null && url.ref == null &&
            url.protocol.equals(origin.protocol, true) && url.host.equals(origin.host, true) &&
            port(url) == port(origin)) { "Cross-origin artifact rejected" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
        }
        active = connection
        try {
            check(!closed) { "Request cancelled" }
            val status = connection.responseCode
            require(status in 200..299) { "HTTP $status" }
            val expected = connection.contentLengthLong
            require(expected <= limit) { "Response exceeds $limit bytes" }
            var received = 0L
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    check(!closed) { "Request cancelled" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= limit) { "Response exceeds $limit bytes" }
                    output.write(buffer, 0, count)
                }
            }
            require(received > 0 && (expected < 0 || received == expected)) { "Empty or truncated response" }
        } finally {
            active = null
            connection.disconnect()
        }
    }

    override fun close() {
        closed = true
        active?.disconnect()
    }

    private fun port(url: URL) = if (url.port >= 0) url.port else url.defaultPort
}
