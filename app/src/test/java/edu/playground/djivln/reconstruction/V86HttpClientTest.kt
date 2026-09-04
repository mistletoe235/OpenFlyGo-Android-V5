package edu.playground.djivln.reconstruction

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class V86HttpClientTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private val requests = CopyOnWriteArrayList<Request>()

    data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    @Before
    fun startServer() {
        server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            runCatching {
                repeat(2) {
                    val socket = server.accept()
                    socket.use { connection ->
                    val input = BufferedInputStream(connection.getInputStream())
                    val requestLine = readLine(input).split(' ')
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) headers[line.substring(0, separator)] = line.substring(separator + 1).trim()
                    }
                    val length = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }
                        ?.value?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < body.size) {
                        val read = input.read(body, offset, body.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    requests += Request(
                        method = requestLine[0],
                        path = requestLine[1],
                        authorization = headers.header("Authorization"),
                        headers = headers,
                        body = body,
                    )
                    val response = if (requestLine[0] == "POST") """
                        {"id":"s20260828-smoke","phase":"receiving","progress":0.0,"message":"ready","image_count":0,"sealed":false,"running":false,"completed":false,"preview_ready":false,"error":null}
                    """.trimIndent() else """{"duplicate":false,"image_count":1}"""
                    val responseBytes = response.toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 201 Created\r\n".toByteArray(Charsets.US_ASCII))
                        write("Content-Type: application/json\r\n".toByteArray(Charsets.US_ASCII))
                        write("Content-Length: ${responseBytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                        write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(responseBytes)
                        flush()
                    }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun stopServer() {
        server.close()
        serverThread.join(1_000)
    }

    @Test
    fun `client sends bearer and exact image telemetry headers`() {
        val client = V86HttpClient("http://127.0.0.1:${server.localPort}", "secret-code")
        val state = client.createSession(
            V86SessionConfig("smoke", 82.1, 6.5, "DJI Mini 4 Pro", maximumTasks = 6),
        )
        assertEquals("s20260828-smoke", state.id)

        val image = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        try {
            image.writeBytes(ByteArray(256) { it.toByte() })
            client.uploadImage(
                sessionId = state.id,
                sequence = 7,
                file = image,
                filename = "frame 7.jpg",
                mimeType = "image/jpeg",
                latitude = 31.18,
                longitude = 121.47,
                absoluteAltitudeMeters = 42.5,
                timestamp = "2026-08-28T06:00:00.000Z",
                captureView = "LEFT_OBLIQUE",
            )
        } finally {
            image.delete()
        }

        assertEquals(2, requests.size)
        assertTrue(requests.all { it.authorization == "Bearer secret-code" })
        val create = requests[0]
        assertEquals("POST", create.method)
        assertEquals("/api/sessions", create.path)
        assertEquals(82.1, JSONObject(create.body.toString(Charsets.UTF_8)).getDouble("horizontal_fov_deg"), 0.0)
        val upload = requests[1]
        assertEquals("PUT", upload.method)
        assertEquals("/api/sessions/s20260828-smoke/images/7", upload.path)
        assertEquals("31.18", upload.headers.header("X-Latitude"))
        assertEquals("121.47", upload.headers.header("X-Longitude"))
        assertEquals("42.5", upload.headers.header("X-Altitude"))
        assertEquals("LEFT_OBLIQUE", upload.headers.header("X-Capture-View"))
        assertEquals(256, upload.body.size)
    }

    @Test
    fun `offline upload lets server read GPS from preserved EXIF`() {
        val client = V86HttpClient("http://127.0.0.1:${server.localPort}", "secret-code")
        val state = client.createSession(V86SessionConfig("offline", 82.1, 6.5, "DJI", maximumTasks = 6))
        val image = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        try {
            image.writeBytes(ByteArray(256) { it.toByte() })
            client.uploadImage(
                sessionId = state.id,
                sequence = 0,
                file = image,
                filename = "DJI_0001.jpg",
                mimeType = "image/jpeg",
                latitude = null,
                longitude = null,
                absoluteAltitudeMeters = null,
                timestamp = null,
                captureView = "NADIR",
            )
        } finally {
            image.delete()
        }

        val upload = requests.single { it.method == "PUT" }
        assertNull(upload.headers.header("X-Latitude"))
        assertNull(upload.headers.header("X-Longitude"))
        assertNull(upload.headers.header("X-Altitude"))
        assertNull(upload.headers.header("X-Timestamp"))
        assertEquals("NADIR", upload.headers.header("X-Capture-View"))
    }

    @Test
    fun `artifact download cannot leak bearer to another origin`() {
        val client = V86HttpClient("http://127.0.0.1:${server.localPort}", "secret-code")
        val result = runCatching { client.download("http://example.com/artifact.ply") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("different origin"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `artifact download streams to file and reports final progress`() {
        val body = ByteArray(512 * 1024) { (it % 251).toByte() }
        withArtifactServer(body, declaredLength = body.size) { port ->
            val root = kotlin.io.path.createTempDirectory("v86-download").toFile()
            try {
                val target = root.resolve("cloud.ply").apply { writeText("old") }
                var lastProgress = 0L
                val downloaded = V86HttpClient("http://127.0.0.1:$port", "secret-code")
                    .downloadToFile("/cloud.ply", target) { bytes, _ -> lastProgress = bytes }
                assertEquals(target, downloaded)
                assertEquals(body.size.toLong(), lastProgress)
                assertTrue(body.contentEquals(target.readBytes()))
                assertFalse(root.resolve("cloud.ply.part").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `truncated artifact never replaces valid cache or leaves part file`() {
        val partial = ByteArray(8 * 1024) { 7 }
        withArtifactServer(partial, declaredLength = partial.size * 4) { port ->
            val root = kotlin.io.path.createTempDirectory("v86-truncated").toFile()
            try {
                val target = root.resolve("cloud.ply").apply { writeText("known-good") }
                val result = runCatching {
                    V86HttpClient("http://127.0.0.1:$port", "secret-code")
                        .downloadToFile("/cloud.ply", target) { _, _ -> }
                }
                assertTrue(result.isFailure)
                assertEquals("known-good", target.readText())
                assertFalse(root.resolve("cloud.ply.part").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun withArtifactServer(
        body: ByteArray,
        declaredLength: Int,
        block: (port: Int) -> Unit,
    ) {
        val artifactServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                artifactServer.accept().use { connection ->
                    val input = BufferedInputStream(connection.getInputStream())
                    while (readLine(input).isNotEmpty()) Unit
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
                        write("Content-Type: application/octet-stream\r\n".toByteArray(Charsets.US_ASCII))
                        write("Content-Length: $declaredLength\r\n".toByteArray(Charsets.US_ASCII))
                        write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            block(artifactServer.localPort)
        } finally {
            artifactServer.close()
            thread.join(1_000)
        }
    }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, true) }?.value
}
