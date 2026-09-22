package edu.playground.djivln.reconstruction

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class V86RemoteSessionClientTest {
    @Test fun `invalid connections fail before network`() {
        for ((endpoint, session, token) in listOf(
            Triple("http://user:pass@example.com", "session_123", "secret"),
            Triple("https://example.com/api", "session_123", "secret"),
            Triple("https://example.com", "../path", "secret"),
            Triple("https://example.com", "session_123", "bad\r\nheader"),
        )) assertTrue(runCatching { V86RemoteSessionClient(endpoint, session, token) }.isFailure)
    }

    @Test fun `result is authenticated read only GET`() {
        Server("""{"session_id":"session_123","phase":"complete"}""").use { server ->
            V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                assertEquals("session_123", client.result().sessionId)
                assertEquals("GET /api/sessions/session_123/result HTTP/1.1", server.headers.first())
                assertTrue(server.headers.contains("Authorization: Bearer secret"))
            }
        }
    }

    @Test fun `wrong session and failed HTTP are rejected`() {
        for ((body, status) in listOf("""{"session_id":"session_other"}""" to 200, "denied" to 401)) {
            Server(body, status).use { server ->
                V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                    assertTrue(runCatching { client.result() }.isFailure)
                }
            }
        }
    }

    @Test fun `redirect is never followed`() {
        Server("moved", 302, extra = "Location: http://127.0.0.1:1/stolen\r\n").use { server ->
            V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                assertEquals("HTTP 302", runCatching { client.result() }.exceptionOrNull()?.message)
            }
        }
    }

    @Test fun `cross origin artifacts cannot receive token`() {
        V86RemoteSessionClient("https://example.com", "session_123", "secret").use { client ->
            for (path in listOf("https://other.example/mission", "//other.example/mission",
                "http://example.com/mission", "https://example.com:8443/mission", "https://user@example.com/mission")) {
                assertEquals("Cross-origin artifact rejected", runCatching {
                    client.mission(result().copy(missionUrl = path))
                }.exceptionOrNull()?.message)
            }
        }
    }

    @Test fun `test only results block mission but retain cloud`() {
        for (extra in listOf("\"test_only\":true", "\"contract\":{\"altitude_mode\":\"relative_height_test\"}")) {
            val result = V86Result.decode(JSONObject("""{"session_id":"session_123",$extra,
                "point_cloud":{"url":"/cloud.ply"},
                "openfly_v5_mission":{"url":"/mission.json","safe_to_execute":true}}"""))
            assertTrue(result.relativeHeightTest)
            assertFalse(result.safeToExecute)
            assertNull(result.missionUrl)
            assertEquals("/cloud.ply", result.pointCloudUrl)
        }
        V86RemoteSessionClient("http://127.0.0.1:1", "session_123", "secret").use { client ->
            assertEquals("Relative-height tests cannot provide flight missions", runCatching {
                client.mission(result().copy(relativeHeightTest = true))
            }.exceptionOrNull()?.message)
            assertEquals("Session changed; refresh first", runCatching {
                client.mission(result().copy(sessionId = "another_session"))
            }.exceptionOrNull()?.message)
        }
    }

    @Test fun `mission is downloaded without rewriting review metadata`() {
        val raw = """{"execution_review":{"safe_to_execute":false},"schema_version":14}"""
        Server(raw).use { server ->
            V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                assertEquals(raw, client.mission(result().copy(safeToExecute = false)))
                assertEquals("GET /mission.json HTTP/1.1", server.headers.first())
            }
        }
    }

    @Test fun `oversized declared result fails`() {
        Server("", length = 1024 * 1024 + 1).use { server ->
            V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                assertTrue(runCatching { client.result() }.exceptionOrNull()?.message.orEmpty().contains("exceeds"))
            }
        }
    }

    @Test fun `oversized unknown length result fails while streaming`() {
        Server("x".repeat(1024 * 1024 + 1), length = null).use { server ->
            V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                assertTrue(runCatching { client.result() }.exceptionOrNull()?.message.orEmpty().contains("exceeds"))
            }
        }
    }

    @Test fun `truncated ply preserves existing file and removes part`() {
        val root = kotlin.io.path.createTempDirectory("remote-ply").toFile()
        try {
            val target = root.resolve("cloud.ply").apply { writeText("previous") }
            Server("ply", length = 100).use { server ->
                V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                    assertTrue(runCatching { client.pointCloud(result(), target) }.isFailure)
                    assertEquals("previous", target.readText())
                    assertFalse(root.resolve("cloud.ply.part").exists())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `point cloud replaces bounded cache`() {
        val root = kotlin.io.path.createTempDirectory("remote-ply").toFile()
        try {
            val target = root.resolve("cloud.ply").apply { writeText("previous") }
            Server("ply\nformat ascii 1.0\n").use { server ->
                V86RemoteSessionClient(server.endpoint, "session_123", "secret").use { client ->
                    assertEquals(target, client.pointCloud(result(), target))
                    assertTrue(target.readText().startsWith("ply\n"))
                    assertFalse(root.resolve("cloud.ply.part").exists())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `closed client cannot start request`() {
        val client = V86RemoteSessionClient("http://127.0.0.1:1", "session_123", "secret")
        client.close()
        assertEquals("Request cancelled", runCatching { client.result() }.exceptionOrNull()?.message)
    }

    private fun result() = V86Result.decode(JSONObject("""{"session_id":"session_123",
        "point_cloud":{"url":"/cloud.ply"},
        "openfly_v5_mission":{"url":"/mission.json","safe_to_execute":true}}"""))

    private class Server(body: String, status: Int = 200, length: Int? = body.toByteArray().size,
                         extra: String = "") : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val endpoint = "http://127.0.0.1:${server.localPort}"
        val headers = CopyOnWriteArrayList<String>()
        private val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    socket.getOutputStream().use { output ->
                        val header = "HTTP/1.1 $status Test\r\nConnection: close\r\n" +
                            (length?.let { "Content-Length: $it\r\n" } ?: "") + extra + "\r\n"
                        output.write(header.toByteArray())
                        output.write(body.toByteArray())
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() { server.close(); thread.join(3000) }
    }
}
