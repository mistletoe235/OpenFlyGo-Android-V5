package edu.playground.djivln.reconstruction

import java.io.File
import org.json.JSONObject

/** Replays actual mission view labels; pass numbers do not imply a camera direction. */
object V86ReplayCaptureViewCatalog {
    private val filename = Regex("^\\d{8}_\\d{6}_\\d{3}_(.+)_p(-?\\d+)_wp-?\\d+_\\d+\\.(?:jpg|jpeg)$", RegexOption.IGNORE_CASE)

    fun keyForName(name: String): String? = filename.matchEntire(name)?.let {
        "${it.groupValues[1]}:${it.groupValues[2]}"
    }

    fun read(directory: File): Map<String, String> = buildMap {
        listOf("previous.jsonl", "current.jsonl").forEach { name ->
            val file = File(directory, name)
            if (file.isFile) file.useLines { lines -> lines.forEach { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let { row ->
                    if (row.optString("type") == "trigger_frame_saved" && row.has("pass_index")) {
                        val mission = row.optString("mission_id").take(32)
                        val view = row.optString("capture_view")
                        if (mission.isNotBlank() && view in setOf("NADIR", "FORWARD_OBLIQUE", "BACKWARD_OBLIQUE", "LEFT_OBLIQUE", "RIGHT_OBLIQUE")) {
                            put("$mission:${row.optInt("pass_index", -1)}", view)
                        }
                    }
                }
            } }
        }
    }
}
