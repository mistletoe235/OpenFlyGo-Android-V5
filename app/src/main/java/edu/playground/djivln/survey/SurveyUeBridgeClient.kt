package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SurveyUeBridgeClient(context: Context? = null) : AutoCloseable {
    private val appContext = context?.applicationContext
    fun interface Callback {
        fun onComplete(ok: Boolean, message: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val statePostInFlight = AtomicBoolean(false)

    fun postMission(endpoint: String, mission: SurveyMission, callback: Callback) {
        post(endpoint, "/v1/survey/mission", SurveyUeBridgeContract.encodeMission(mission), callback)
    }

    fun postTelemetry(endpoint: String, telemetry: SurveyUeTelemetry, callback: Callback): Boolean {
        if (!statePostInFlight.compareAndSet(false, true)) return false
        post(endpoint, "/v1/survey/telemetry", SurveyUeBridgeContract.encodeTelemetry(telemetry)) { ok, message ->
            statePostInFlight.set(false)
            callback.onComplete(ok, message)
        }
        return true
    }

    fun postTarget(endpoint: String, target: SurveyUeTarget, callback: Callback) {
        post(endpoint, "/v1/survey/target", SurveyUeBridgeContract.encodeTarget(target), callback)
    }

    fun postCapture(endpoint: String, capture: SurveyUeCapture, callback: Callback) {
        post(endpoint, "/v1/survey/capture", SurveyUeBridgeContract.encodeCapture(capture), callback)
    }

    private fun post(endpoint: String, path: String, json: String, callback: Callback) {
        executor.execute {
            try {
                val base = URI(endpoint.trim())
                require(base.scheme == "http" || base.scheme == "https") {
                    "endpoint must start with http:// or https://"
                }
                require(base.host != null) { "endpoint host is invalid" }
                val requestPath = base.path.orEmpty().removeSuffix("/") + path
                val requestUrl = URI(
                    base.scheme, base.userInfo, base.host, base.port, requestPath, base.query, null,
                ).toURL()
                val connection = requestUrl.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = json.toByteArray(StandardCharsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val code = connection.responseCode
                    callback.onComplete(code in 200..299, "HTTP $code")
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                callback.onComplete(false, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT = 30_010

        /** Reuse the UDP-authenticated HIL peer while retaining configured scheme/port/path. */
        fun resolvedEndpoint(
            configuredEndpoint: String,
            authenticatedPeerHost: String?,
            context: Context? = null,
        ): String {
            val configured = configuredEndpoint.trim().removeSuffix("/")
            val peer = authenticatedPeerHost?.trim()?.removePrefix("[")?.removeSuffix("]")
                ?.takeIf(String::isNotBlank)
            if (configured.isBlank()) {
                require(peer != null) {
                    context?.getString(R.string.ue_hil_endpoint_missing_no_peer)
                        ?: "UE HIL endpoint is empty and no authenticated peer has been discovered"
                }
                val displayHost = if (peer.contains(':')) "[$peer]" else peer
                return "http://$displayHost:$DEFAULT_PORT"
            }
            val source = URI(configured)
            require(source.scheme == "http" || source.scheme == "https") {
                "endpoint must start with http:// or https://"
            }
            require(source.host != null) { "endpoint host is invalid" }
            if (peer == null) return configured
            return URI(
                source.scheme,
                source.userInfo,
                peer,
                source.port,
                source.path,
                source.query,
                source.fragment,
            ).toString().removeSuffix("/")
        }
    }
}
