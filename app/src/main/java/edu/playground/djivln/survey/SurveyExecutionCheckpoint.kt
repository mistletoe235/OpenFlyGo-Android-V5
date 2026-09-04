package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import org.json.JSONObject

data class SurveyExecutionCheckpoint(
    val missionId: String,
    val waypointIndex: Int,
    val state: SurveyExecutionState,
    val updatedAtEpochMillis: Long,
    val executionLegIndex: Int = waypointIndex,
    val phase: SurveyExecutionPhase = SurveyExecutionPhase.SURVEY,
    val recoveryPoint: GeoPoint? = null,
    val backend: SurveyExecutionBackend? = null,
    /** Stable ordinal inside [phase]; unlike executionLegIndex it does not shift with launch legs. */
    val phaseLegOrdinal: Int? = null,
    val djiBreakpoint: WaylineBreakpoint? = null,
) {
    init {
        require(missionId.isNotBlank())
        require(waypointIndex >= 0)
        require(executionLegIndex >= 0)
        require(phaseLegOrdinal == null || phaseLegOrdinal >= 0)
    }
}

object SurveyExecutionCheckpointJson {
    private const val SCHEMA_VERSION = 6

    fun encode(value: SurveyExecutionCheckpoint): String = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("mission_id", value.missionId)
        .put("waypoint_index", value.waypointIndex)
        .put("state", value.state.name)
        .put("updated_at_epoch_ms", value.updatedAtEpochMillis)
        .put("execution_leg_index", value.executionLegIndex)
        .put("phase", value.phase.name)
        .apply {
            value.backend?.let { put("backend", it.name) }
            value.phaseLegOrdinal?.let { put("phase_leg_ordinal", it) }
            value.djiBreakpoint?.let { breakpoint ->
                put("dji_wayline_id", breakpoint.waylineId)
                put("dji_waypoint_id", breakpoint.waypointId)
                put("dji_segment_progress", breakpoint.segmentProgress)
                breakpoint.latitude?.let { put("dji_latitude", it) }
                breakpoint.longitude?.let { put("dji_longitude", it) }
                breakpoint.altitudeMeters?.let { put("dji_altitude_m", it) }
                breakpoint.recoverActionType?.let { put("dji_recover_action", it) }
            }
            value.recoveryPoint?.let {
                put("recovery_latitude", it.latitude)
                put("recovery_longitude", it.longitude)
                put("recovery_altitude_m", it.altitudeMeters)
            }
        }
        .toString()

    fun decode(raw: String): SurveyExecutionCheckpoint {
        val root = JSONObject(raw)
        val schemaVersion = root.getInt("schema_version")
        require(schemaVersion in 1..SCHEMA_VERSION) { "unsupported checkpoint schema" }
        return SurveyExecutionCheckpoint(
            missionId = root.getString("mission_id"),
            waypointIndex = root.getInt("waypoint_index"),
            state = SurveyExecutionState.valueOf(root.getString("state")),
            updatedAtEpochMillis = root.getLong("updated_at_epoch_ms"),
            executionLegIndex = if (schemaVersion >= 2) root.getInt("execution_leg_index")
                // Sentinel forces the state machine to remap the legacy
                // mission waypoint index after safe-transit legs are built.
                else Int.MAX_VALUE,
            phase = if (schemaVersion >= 2) SurveyExecutionPhase.valueOf(root.getString("phase"))
                else SurveyExecutionPhase.SURVEY,
            recoveryPoint = if (schemaVersion >= 3 && root.has("recovery_latitude")) {
                GeoPoint(
                    root.getDouble("recovery_latitude"),
                    root.getDouble("recovery_longitude"),
                    root.getDouble("recovery_altitude_m"),
                )
            } else null,
            backend = if (schemaVersion >= 4 && root.has("backend")) {
                SurveyExecutionBackend.valueOf(root.getString("backend"))
            } else null,
            phaseLegOrdinal = if (schemaVersion >= 5 && root.has("phase_leg_ordinal")) {
                root.getInt("phase_leg_ordinal")
            } else null,
            djiBreakpoint = if (schemaVersion >= 6 && root.has("dji_wayline_id")) {
                WaylineBreakpoint(
                    waylineId = root.getInt("dji_wayline_id"),
                    waypointId = root.getInt("dji_waypoint_id"),
                    segmentProgress = root.getDouble("dji_segment_progress"),
                    latitude = root.optDoubleOrNull("dji_latitude"),
                    longitude = root.optDoubleOrNull("dji_longitude"),
                    altitudeMeters = root.optDoubleOrNull("dji_altitude_m"),
                    recoverActionType = root.optString("dji_recover_action").takeIf { it.isNotBlank() },
                )
            } else null,
        )
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null
}

data class SurveyRecoveryPosition(
    val waypointIndex: Int,
    val executionLegIndex: Int,
)

object SurveyCheckpointRecoveryPolicy {
    fun position(
        waypointIndex: Int,
        executionLegIndex: Int,
        state: SurveyExecutionState,
        phase: SurveyExecutionPhase,
        targetCaptureAction: CaptureAction,
        hasRecoveryPoint: Boolean,
        stripStartWaypointIndex: Int? = null,
        stripStartExecutionLegIndex: Int? = null,
    ): SurveyRecoveryPosition {
        val interruptedStripEnd = state in setOf(
            SurveyExecutionState.PAUSED,
            SurveyExecutionState.RUNNING,
        ) &&
            phase == SurveyExecutionPhase.SURVEY &&
            targetCaptureAction == CaptureAction.STOP_DISTANCE_INTERVAL &&
            !hasRecoveryPoint &&
            waypointIndex > 0 && executionLegIndex > 0
        return if (interruptedStripEnd) {
            SurveyRecoveryPosition(
                stripStartWaypointIndex?.takeIf { it in 0 until waypointIndex } ?: waypointIndex - 1,
                stripStartExecutionLegIndex?.takeIf { it in 0 until executionLegIndex }
                    ?: executionLegIndex - 1,
            )
        } else {
            SurveyRecoveryPosition(waypointIndex, executionLegIndex)
        }
    }
}
