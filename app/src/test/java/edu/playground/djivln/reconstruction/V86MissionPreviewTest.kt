package edu.playground.djivln.reconstruction

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V86MissionPreviewTest {
    private fun mission() = JSONObject("""{"name":"Preview","schema_version":13,"coordinate_frame":"WGS84",
        "execution_review":{"safe_to_execute":false,"flight_authorized":false},
        "waypoints":[{"point":{"latitude":31.0,"longitude":121.0,"altitude_m":30.0}},
        {"point":{"latitude":31.001,"longitude":121.001,"altitude_m":35.0}}]}""")

    private fun result(approved: Boolean = false) = V86Result.decode(JSONObject("""{"session_id":"session_123",
        "openfly_v5_mission":{"url":"/mission.json","safe_to_execute":$approved}}"""))

    @Test fun `unapproved mission renders without changing approval`() {
        val raw = mission().toString()
        val preview = V86MissionPreview.decode(raw)
        assertEquals("Preview", preview.name)
        assertEquals(2, preview.points.size)
        assertEquals(35.0, preview.points.last().altitudeMeters, 0.0)
        assertFalse(V86MissionPreview.canImport(result(), raw))
        assertFalse(V86MissionPreview.canImport(result(true), raw))
        assertFalse(JSONObject(raw).getJSONObject("execution_review").getBoolean("safe_to_execute"))
    }

    @Test fun `review payload cannot override false envelope or preview flag`() {
        val root = mission().put("execution_review", JSONObject().put("safe_to_execute", true).put("flight_authorized", true))
        assertFalse(V86MissionPreview.canImport(result(), root.toString()))
        assertTrue(V86MissionPreview.canImport(result(true), root.toString()))
        root.put("export_review", JSONObject().put("preview_only", true))
        assertFalse(V86MissionPreview.canImport(result(true), root.toString()))
        root.remove("export_review")
        root.put("execution_review", JSONObject.NULL)
        assertFalse(V86MissionPreview.canImport(result(true), root.toString()))
        assertFalse(V86MissionPreview.canImport(result(true).copy(relativeHeightTest = true), root.toString()))
    }

    @Test fun `preview validates schema frame and coordinates`() {
        assertTrue(runCatching { V86MissionPreview.decode(mission().put("schema_version", 15).toString()) }.isFailure)
        assertTrue(runCatching { V86MissionPreview.decode(mission().put("coordinate_frame", "ENU").toString()) }.isFailure)
        val root = mission()
        root.getJSONArray("waypoints").getJSONObject(0).getJSONObject("point").put("latitude", 100)
        assertTrue(runCatching { V86MissionPreview.decode(root.toString()) }.isFailure)
        assertTrue(runCatching { V86MissionPreview.decode(mission().put("waypoints", org.json.JSONArray()).toString()) }.isFailure)
    }

    @Test fun `preview respects platform schema boundary`() {
        val raw = mission().put("schema_version", 14).toString()
        assertEquals(2, V86MissionPreview.decode(raw).points.size)
    }
}
