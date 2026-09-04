package edu.playground.djivln.survey

import edu.playground.djivln.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyExternalInterventionPolicyTest {
    @Test
    fun pausesAtSmartRthCountdownBeforeFlightModeChanges() {
        assertEquals(
            R.string.survey_smart_low_battery_rth,
            SurveyExternalInterventionPolicy.reason("GPS_NORMAL", "IDLE", "COUNTING_DOWN")?.resourceId,
        )
    }

    @Test
    fun ignoresIdleAndCancelledSmartRth() {
        assertNull(SurveyExternalInterventionPolicy.reason("GPS_NORMAL", "IDLE", "CANCELLED"))
    }

    @Test
    fun recognizesGoHomeStateWithoutStringMatchingFlightMode() {
        assertEquals(
            R.string.survey_fc_rth_or_landing,
            SurveyExternalInterventionPolicy.reason("GPS_NORMAL", "RETURNING_TO_HOME", "IDLE")?.resourceId,
        )
    }
}
