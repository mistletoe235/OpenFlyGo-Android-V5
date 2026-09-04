package edu.playground.djivln.survey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyGimbalSettlePolicyTest {
    @Test
    fun `pitch within tolerance is settled`() {
        assertTrue(SurveyGimbalSettlePolicy.isSettled(-45.0, -42.0))
        assertFalse(SurveyGimbalSettlePolicy.isSettled(-45.0, -41.9))
    }

    @Test
    fun `capture waits for accepted command and minimum settling time`() {
        assertFalse(SurveyGimbalSettlePolicy.isVerifiedForCapture(-45.0, -45.0, 0L, 5_000L))
        assertFalse(SurveyGimbalSettlePolicy.isVerifiedForCapture(-45.0, -45.0, 4_000L, 5_199L))
        assertTrue(SurveyGimbalSettlePolicy.isVerifiedForCapture(-45.0, -45.0, 4_000L, 5_200L))
    }

    @Test
    fun `stalled command is retried at bounded interval`() {
        assertFalse(SurveyGimbalSettlePolicy.shouldRetry(2_999L, 1_000L))
        assertTrue(SurveyGimbalSettlePolicy.shouldRetry(3_000L, 1_000L))
    }

    @Test
    fun `settling has a hard timeout`() {
        assertFalse(SurveyGimbalSettlePolicy.hasTimedOut(20_999L, 1_000L))
        assertTrue(SurveyGimbalSettlePolicy.hasTimedOut(21_000L, 1_000L))
    }

    @Test
    fun `timeout tracks only a continuous unsettled interval`() {
        var since = SurveyGimbalSettlePolicy.updateUnsettledSince(1_000L, 0L, settled = false)
        assertTrue(since == 1_000L)
        since = SurveyGimbalSettlePolicy.updateUnsettledSince(5_000L, since, settled = true)
        assertTrue(since == 0L)
        since = SurveyGimbalSettlePolicy.updateUnsettledSince(25_000L, since, settled = false)
        assertTrue(since == 25_000L)
        assertFalse(SurveyGimbalSettlePolicy.hasTimedOut(25_001L, since))
    }
}
