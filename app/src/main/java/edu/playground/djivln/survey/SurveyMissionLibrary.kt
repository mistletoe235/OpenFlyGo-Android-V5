package edu.playground.djivln.survey

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SurveyMissionVersion(
    val versionId: String = UUID.randomUUID().toString(),
    val missionName: String,
    val revision: Int,
    val savedAtEpochMillis: Long,
    val missionJson: String,
) {
    init {
        require(versionId.isNotBlank())
        require(missionName.isNotBlank())
        require(revision > 0)
    }

    fun mission(): SurveyMission = SurveyMissionJson.decode(missionJson)
}

object SurveyMissionLibrary {
    const val MAX_VERSIONS = 50
    private const val SCHEMA_VERSION = 1

    fun addVersion(
        existing: List<SurveyMissionVersion>,
        mission: SurveyMission,
        savedAtEpochMillis: Long = System.currentTimeMillis(),
    ): List<SurveyMissionVersion> {
        val revision = (existing.asSequence()
            .filter { it.missionName == mission.name }
            .maxOfOrNull { it.revision } ?: 0) + 1
        val added = SurveyMissionVersion(
            missionName = mission.name,
            revision = revision,
            savedAtEpochMillis = savedAtEpochMillis,
            missionJson = SurveyMissionJson.encode(mission),
        )
        return (existing + added)
            .sortedByDescending { it.savedAtEpochMillis }
            .take(MAX_VERSIONS)
    }

    fun encode(versions: List<SurveyMissionVersion>): String = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("versions", JSONArray().apply {
            versions.forEach { version ->
                put(JSONObject()
                    .put("version_id", version.versionId)
                    .put("mission_name", version.missionName)
                    .put("revision", version.revision)
                    .put("saved_at_epoch_ms", version.savedAtEpochMillis)
                    .put("mission_json", version.missionJson))
            }
        })
        .toString()

    fun decode(raw: String?): List<SurveyMissionVersion> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = JSONObject(raw)
        require(root.getInt("schema_version") == SCHEMA_VERSION) { "unsupported mission library schema" }
        val array = root.getJSONArray("versions")
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            SurveyMissionVersion(
                versionId = value.getString("version_id"),
                missionName = value.getString("mission_name"),
                revision = value.getInt("revision"),
                savedAtEpochMillis = value.getLong("saved_at_epoch_ms"),
                missionJson = value.getString("mission_json"),
            )
        }
    }
}
