package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.domain.wayline.WaylineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyWaylineExportTest {
    @Test fun executingWaylineProducesRecoverableCheckpoint() {
        val mission = SurveyRegressionMissionFactory.create(GeoPoint(31.0, 121.0), false)
        val checkpoint = SurveyWaylineExport.checkpoint(
            mission,
            WaylineState(phase = WaylinePhase.EXECUTING, waypointIndex = 2),
            nowEpochMillis = 123L,
        )
        assertEquals(mission.id, checkpoint?.missionId)
        assertEquals(2, checkpoint?.waypointIndex)
        assertEquals(SurveyExecutionState.RUNNING, checkpoint?.state)
    }

    @Test fun missingOrInvalidWaypointDoesNotInventCheckpoint() {
        val mission = SurveyRegressionMissionFactory.create(GeoPoint(31.0, 121.0), false)
        assertNull(SurveyWaylineExport.checkpoint(mission, WaylineState(), 123L))
        assertNull(SurveyWaylineExport.checkpoint(
            mission,
            WaylineState(phase = WaylinePhase.PREPARING, waypointIndex = 0),
            123L,
        ))
        assertNull(SurveyWaylineExport.checkpoint(
            mission,
            WaylineState(waypointIndex = mission.waypoints.size),
            123L,
        ))
    }

    @Test fun djiBreakpointIsPreservedInExportedCheckpoint() {
        val mission = SurveyRegressionMissionFactory.create(GeoPoint(31.0, 121.0), false)
        val breakpoint = WaylineBreakpoint(0, 2, 0.4)
        val checkpoint = SurveyWaylineExport.checkpoint(
            mission,
            WaylineState(
                phase = WaylinePhase.PAUSED,
                waypointIndex = 2,
                breakpoint = breakpoint,
            ),
            nowEpochMillis = 123L,
        )

        assertEquals(breakpoint, checkpoint?.djiBreakpoint)
    }
}
