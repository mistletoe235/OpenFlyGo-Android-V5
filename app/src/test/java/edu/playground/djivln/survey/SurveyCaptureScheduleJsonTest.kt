package edu.playground.djivln.survey

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyCaptureScheduleJsonTest {
    @Test
    fun `JSON contains mission and ordered capture events`() {
        val mission = SurveyRegressionMissionFactory.create(GeoPoint(31.2304, 121.4737), true)
        val events = SurveyCaptureSchedule.build(mission)
        val json = JSONObject(SurveyCaptureScheduleJson.encode(mission, events))

        assertEquals(1, json.getInt("schema_version"))
        assertEquals(events.size, json.getJSONArray("capture_events").length())
        assertEquals(0, json.getJSONArray("capture_events").getJSONObject(0).getInt("capture_index"))
    }
}
