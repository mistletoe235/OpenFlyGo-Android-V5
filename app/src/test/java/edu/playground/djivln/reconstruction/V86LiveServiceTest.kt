package edu.playground.djivln.reconstruction

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class V86LiveServiceTest {
    @Test fun `configured live service negotiates sessions and serves preview`() {
        val endpoint = System.getenv("OPENFLYSCAN_LIVE_ENDPOINT")
        val token = System.getenv("OPENFLYSCAN_LIVE_TOKEN")
        val reference = System.getenv("OPENFLYSCAN_LIVE_SESSION")
        assumeTrue("Set live service environment to run this opt-in check", !endpoint.isNullOrBlank() && !token.isNullOrBlank() && !reference.isNullOrBlank())
        val client = V86HttpClient(endpoint!!, token!!)
        val configs = edu.playground.djivln.survey.RecaptureFlightMode.entries.map { mode ->
            V86SessionConfig("OpenFlyScan API check - no flight", 70.0, 25.0, "test", autoPreview = false, recaptureFlightMode = mode)
        }
        for (config in configs) {
            val session = client.createSession(config)
            try {
                val state = JSONObject(String(client.download("/api/sessions/${session.id}"), Charsets.UTF_8))
                assertEquals(config.toJson().getString("recapture_flight_mode"), state.getJSONObject("config").getString("recapture_flight_mode"))
                assertEquals(0, session.imageCount)
            } finally {
                assertEquals("cancelled", client.cancel(session.id).phase)
            }
        }
        V86RemoteSessionClient(endpoint, reference!!, token).use { browser ->
            val result = browser.result()
            assertFalse(result.safeToExecute)
            val raw = browser.mission(result)
            assertTrue(V86MissionPreview.decode(raw).points.isNotEmpty())
            assertFalse(V86MissionPreview.canImport(result, raw))
        }
    }
}
