package edu.playground.djivln.reconstruction

import edu.playground.djivln.survey.GeoPoint
import org.json.JSONObject

data class V86MissionPreview(val name: String, val points: List<GeoPoint>) {
    companion object {
        fun decode(raw: String): V86MissionPreview {
            val root = JSONObject(raw)
            require(root.getInt("schema_version") in 1..14) { "Unsupported mission schema" }
            require(root.getString("coordinate_frame") == "WGS84") { "Only WGS84 missions are supported" }
            val rows = root.getJSONArray("waypoints")
            require(rows.length() in 1..20000) { "Preview requires 1–20000 waypoints" }
            val points = List(rows.length()) { index ->
                val point = rows.getJSONObject(index).getJSONObject("point")
                GeoPoint(point.getDouble("latitude"), point.getDouble("longitude"), point.getDouble("altitude_m"))
            }
            return V86MissionPreview(root.getString("name"), points)
        }

        fun canImport(result: V86Result, raw: String): Boolean {
            if (result.relativeHeightTest || !result.safeToExecute) return false
            val root = JSONObject(raw)
            if (root.has("execution_review")) {
                val review = root.optJSONObject("execution_review") ?: return false
                if (review.opt("safe_to_execute") != true) return false
                if (review.has("flight_authorized") && review.opt("flight_authorized") != true) return false
            }
            val export = root.optJSONObject("export_review")
            return export?.optBoolean("preview_only", false) != true
        }
    }
}
