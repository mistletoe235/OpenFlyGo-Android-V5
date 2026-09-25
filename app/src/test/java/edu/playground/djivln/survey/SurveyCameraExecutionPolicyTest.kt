package edu.playground.djivln.survey

import org.junit.Assert.*
import org.junit.Test

class SurveyCameraExecutionPolicyTest {
    private val allowed = SurveyExecutionGateResult(true, emptySet(), 4.0)

    @Test fun unavailableOrDifferentGeometryIsOnlyAnAdvisory() {
        val assessment = SurveyCameraExecutionPolicy.evaluate(allowed, true, false)
        assertEquals(allowed, assessment.gate)
        assertTrue(assessment.geometryWarning)
        assertFalse(assessment.gate.blocks.contains(SurveyExecutionBlock.CAMERA_GEOMETRY_UNVERIFIED))
    }

    @Test fun matchingGeometryDoesNotNeedAnAdvisory() {
        val assessment = SurveyCameraExecutionPolicy.evaluate(allowed, true, true)
        assertEquals(allowed, assessment.gate)
        assertFalse(assessment.geometryWarning)
    }

    @Test fun disconnectedCameraStillBlocks() {
        val assessment = SurveyCameraExecutionPolicy.evaluate(allowed, false, false)
        assertFalse(assessment.gate.allowed)
        assertTrue(assessment.gate.blocks.contains(SurveyExecutionBlock.CAMERA_UNAVAILABLE))
    }

    @Test fun cameraAdvisoryNeverOverridesFlightProtection() {
        for (block in listOf(SurveyExecutionBlock.TELEMETRY_STALE,
                SurveyExecutionBlock.MANUAL_TAKEOVER, SurveyExecutionBlock.FLIGHT_CONTROLLER_FAILSAFE_ACTIVE)) {
            val blocked = SurveyExecutionGateResult(false, setOf(block), 4.0)
            for (confirmed in listOf(false, true)) {
                assertEquals(blocked, SurveyCameraExecutionPolicy.evaluate(blocked, true, confirmed).gate)
            }
        }
    }
}
