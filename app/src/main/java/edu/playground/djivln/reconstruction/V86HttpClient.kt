package edu.playground.djivln.reconstruction

import edu.playground.djivln.BuildConfig

import android.content.Context
import edu.playground.djivln.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class V86HttpClient(
    endpoint: String,
    private val accessToken: String,
    private val context: Context? = null,
) {
    private val baseUrl = normalizeEndpoint(endpoint, context)
    private val baseAddress = URL(baseUrl)

    fun createSession(config: V86SessionConfig): V86SessionState =
        V86SessionState.decode(requestJson("POST", "/api/sessions", config.toJson()))

    fun getSession(sessionId: String): V86SessionState =
        V86SessionState.decode(requestJson("GET", "/api/sessions/${safeSessionId(sessionId)}"))

    fun uploadImage(
        sessionId: String,
        sequence: Int,
        file: File,
        filename: String,
        mimeType: String,
        latitude: Double?,
        longitude: Double?,
        absoluteAltitudeMeters: Double?,
        altitudeSource: String? = null,
        timestamp: String?,
        captureView: String,
    ): JSONObject {
        require(file.isFile && file.length() >= 128L) {
            message(R.string.v86_upload_image_missing, "The image to upload is missing or empty")
        }
        val connection = open("PUT", "/api/sessions/${safeSessionId(sessionId)}/images/$sequence")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", mimeType)
        connection.setRequestProperty("X-Filename", filename)
        latitude?.let { connection.setRequestProperty("X-Latitude", it.toString()) }
        longitude?.let { connection.setRequestProperty("X-Longitude", it.toString()) }
        absoluteAltitudeMeters?.let { connection.setRequestProperty("X-Altitude", it.toString()) }
        altitudeSource?.takeIf(String::isNotBlank)?.let {
            connection.setRequestProperty("X-Altitude-Source", it)
        }
        timestamp?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("X-Timestamp", it) }
        connection.setRequestProperty("X-Capture-View", captureView)
        connection.setFixedLengthStreamingMode(file.length())
        file.inputStream().buffered().use { input ->
            connection.outputStream.buffered().use { output -> input.copyTo(output) }
        }
        return JSONObject(readResponse(connection))
    }

    fun finalize(sessionId: String): V86SessionState = V86SessionState.decode(
        requestJson("POST", "/api/sessions/${safeSessionId(sessionId)}/finalize", JSONObject()),
    )

    fun retry(sessionId: String): V86SessionState = V86SessionState.decode(
        requestJson("POST", "/api/sessions/${safeSessionId(sessionId)}/retry", JSONObject()),
    )

    fun cancel(sessionId: String): V86SessionState = V86SessionState.decode(
        requestJson("POST", "/api/sessions/${safeSessionId(sessionId)}/cancel", JSONObject()),
    )

    fun result(sessionId: String): V86Result = V86Result.decode(
        requestJson("GET", "/api/sessions/${safeSessionId(sessionId)}/result"),
    )

    fun download(pathOrUrl: String): ByteArray {
        val connection = open("GET", pathOrUrl)
        return readResponseBytes(connection)
    }

    fun downloadToFile(
        pathOrUrl: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        val connection = open("GET", pathOrUrl)
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.trim()?.take(500).orEmpty()
                throw V86HttpException(code, message.ifBlank { connection.responseMessage ?: "HTTP $code" })
            }
            target.parentFile?.mkdirs()
            val total = connection.contentLengthLong.takeIf { it > 0L }
            var downloaded = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            require(downloaded > 0L) { message(R.string.downloaded_file_empty, "Downloaded file is empty") }
            require(total == null || downloaded == total) {
                message(
                    R.string.downloaded_file_incomplete,
                    "Downloaded file is incomplete: received $downloaded / $total bytes",
                    downloaded,
                    total ?: -1L,
                )
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            return target
        } finally {
            connection.disconnect()
            // Success moves/removes the part file. Every failure path must remove it,
            // even when an older valid target already exists.
            if (temporary.isFile) temporary.delete()
        }
    }

    private fun requestJson(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = open(method, path)
        if (body != null) {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        return JSONObject(readResponse(connection))
    }

    private fun open(method: String, pathOrUrl: String): HttpURLConnection {
        val url = if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            URL(pathOrUrl)
        } else {
            URL(baseUrl + if (pathOrUrl.startsWith('/')) pathOrUrl else "/$pathOrUrl")
        }
        require(url.protocol == "http" || url.protocol == "https") {
            message(R.string.service_address_http_only, "Service address supports HTTP/HTTPS only")
        }
        require(sameOrigin(baseAddress, url)) {
            message(R.string.access_code_cross_origin_rejected, "Refusing to send the access code to a different origin")
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
    }

    private fun readResponse(connection: HttpURLConnection): String =
        readResponseBytes(connection).toString(StandardCharsets.UTF_8)

    private fun readResponseBytes(connection: HttpURLConnection): ByteArray {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                ByteArrayOutputStream().use { output ->
                    input.copyTo(output)
                    output.toByteArray()
                }
            } ?: ByteArray(0)
            if (code !in 200..299) {
                val message = bytes.toString(StandardCharsets.UTF_8).trim().take(500)
                throw V86HttpException(code, message.ifBlank { connection.responseMessage ?: "HTTP $code" })
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun safeSessionId(value: String): String {
        require(value.matches(Regex("[a-z0-9][a-z0-9_-]{5,63}"))) {
            message(R.string.session_id_invalid, "Invalid session ID")
        }
        return value
    }

    private fun message(resourceId: Int, fallback: String, vararg arguments: Any): String =
        context?.getString(resourceId, *arguments) ?: fallback

    companion object {
        val DEFAULT_ENDPOINT: String = BuildConfig.V86_DEFAULT_ENDPOINT
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 180_000
        private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024

        fun normalizeEndpoint(value: String, context: Context? = null): String {
            val trimmed = value.trim().trimEnd('/')
            val url = URL(trimmed)
            require(url.protocol == "http" || url.protocol == "https") {
                context?.getString(R.string.service_address_http_only) ?: "Service address supports HTTP/HTTPS only"
            }
            require(url.host.isNotBlank()) {
                context?.getString(R.string.service_address_host_missing) ?: "Service address is missing a host"
            }
            require(url.path.isBlank() || url.path == "/") {
                context?.getString(R.string.service_address_no_api_path) ?: "Service address must not contain an API path"
            }
            require(url.userInfo == null && url.query == null && url.ref == null) {
                context?.getString(R.string.service_address_no_credentials_query_fragment)
                    ?: "Service address must not contain credentials, query parameters, or fragments"
            }
            return trimmed
        }

        private fun sameOrigin(first: URL, second: URL): Boolean =
            first.protocol.equals(second.protocol, ignoreCase = true) &&
                first.host.equals(second.host, ignoreCase = true) &&
                effectivePort(first) == effectivePort(second)

        private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort
    }
}

class V86HttpException(val statusCode: Int, message: String) : Exception("HTTP $statusCode: $message")
