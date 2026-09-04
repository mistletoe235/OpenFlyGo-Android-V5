package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyExecutionCheckpointTest {
    @Test
    fun `checkpoint round trips without changing recovery position`() {
        val original = SurveyExecutionCheckpoint(
            "mission-1", 7, SurveyExecutionState.RUNNING, 123_456L,
            executionLegIndex = 9,
            phase = SurveyExecutionPhase.TRANSIT_TO_START,
            recoveryPoint = GeoPoint(31.2304, 121.4737, 42.5),
            backend = SurveyExecutionBackend.UE_HIL,
            phaseLegOrdinal = 0,
        )

        assertEquals(original, SurveyExecutionCheckpointJson.decode(
            SurveyExecutionCheckpointJson.encode(original),
        ))
    }

    @Test
    fun `dji breakpoint round trips with sdk recovery fields`() {
        val breakpoint = WaylineBreakpoint(
            waylineId = 2,
            waypointId = 7,
            segmentProgress = 0.35,
            latitude = 31.2304,
            longitude = 121.4737,
            altitudeMeters = 42.5,
            recoverActionType = "GoBackToRecordPoint",
        )
        val original = SurveyExecutionCheckpoint(
            missionId = "mission-dji",
            waypointIndex = 7,
            state = SurveyExecutionState.PAUSED,
            updatedAtEpochMillis = 123_456L,
            executionLegIndex = 7,
            backend = SurveyExecutionBackend.DJI_KMZ,
            djiBreakpoint = breakpoint,
        )

        assertEquals(original, SurveyExecutionCheckpointJson.decode(
            SurveyExecutionCheckpointJson.encode(original),
        ))
    }

    @Test
    fun `schema one checkpoint restores as survey leg`() {
        val legacy = """{"schema_version":1,"mission_id":"m","waypoint_index":3,"state":"PAUSED","updated_at_epoch_ms":9}"""
        val decoded = SurveyExecutionCheckpointJson.decode(legacy)

        assertEquals(Int.MAX_VALUE, decoded.executionLegIndex)
        assertEquals(SurveyExecutionPhase.SURVEY, decoded.phase)
        assertEquals(null, decoded.backend)
        assertEquals(null, decoded.phaseLegOrdinal)
        assertEquals(null, decoded.djiBreakpoint)
    }

    @Test
    fun `schema four checkpoint leaves stable phase ordinal unset`() {
        val legacy = """{"schema_version":4,"mission_id":"m","waypoint_index":3,"state":"PAUSED","updated_at_epoch_ms":9,"execution_leg_index":5,"phase":"SURVEY","backend":"UE_HIL"}"""
        val decoded = SurveyExecutionCheckpointJson.decode(legacy)

        assertEquals(5, decoded.executionLegIndex)
        assertEquals(SurveyExecutionBackend.UE_HIL, decoded.backend)
        assertEquals(null, decoded.phaseLegOrdinal)
    }

    @Test
    fun `paused strip resumes from strip start to avoid coverage gaps`() {
        val recovery = SurveyCheckpointRecoveryPolicy.position(
            waypointIndex = 7,
            executionLegIndex = 9,
            state = SurveyExecutionState.PAUSED,
            phase = SurveyExecutionPhase.SURVEY,
            targetCaptureAction = CaptureAction.STOP_DISTANCE_INTERVAL,
            hasRecoveryPoint = false,
        )

        assertEquals(SurveyRecoveryPosition(6, 8), recovery)
    }

    @Test
    fun `paused strip with exact recovery point retains current target`() {
        val recovery = SurveyCheckpointRecoveryPolicy.position(
            waypointIndex = 7,
            executionLegIndex = 9,
            state = SurveyExecutionState.PAUSED,
            phase = SurveyExecutionPhase.SURVEY,
            targetCaptureAction = CaptureAction.STOP_DISTANCE_INTERVAL,
            hasRecoveryPoint = true,
        )

        assertEquals(SurveyRecoveryPosition(7, 9), recovery)
    }

    @Test
    fun `non capture transit checkpoint retains exact target`() {
        assertEquals(
            SurveyRecoveryPosition(7, 9),
            SurveyCheckpointRecoveryPolicy.position(
                7, 9, SurveyExecutionState.PAUSED,
                SurveyExecutionPhase.RETURN_HOME, CaptureAction.NONE, false,
            ),
        )
    }

    @Test
    fun `running strip rewinds to explicit pass start after process death`() {
        assertEquals(
            SurveyRecoveryPosition(4, 6),
            SurveyCheckpointRecoveryPolicy.position(
                7, 9, SurveyExecutionState.RUNNING,
                SurveyExecutionPhase.SURVEY, CaptureAction.STOP_DISTANCE_INTERVAL, false,
                stripStartWaypointIndex = 4,
                stripStartExecutionLegIndex = 6,
            ),
        )
    }
}
