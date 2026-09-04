package edu.playground.djivln.account

import edu.playground.djivln.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiAccountFlightPolicyTest {
    @Test fun loggedInAllowsFlight() {
        assertNull(DjiAccountFlightPolicy.blockingReason(DjiAccountSnapshot(DjiAccountState.LOGGED_IN)))
    }

    @Test fun missingOrExpiredAccountBlocksFlight() {
        assertEquals(
            R.string.dji_account_required_not_logged_in,
            DjiAccountFlightPolicy.blockingReason(DjiAccountSnapshot(DjiAccountState.NOT_LOGGED_IN))!!.resourceId,
        )
        assertEquals(
            R.string.dji_account_required_expired,
            DjiAccountFlightPolicy.blockingReason(DjiAccountSnapshot(DjiAccountState.TOKEN_OUT_OF_DATE))!!.resourceId,
        )
    }

    @Test fun unknownAccountBlocksNewFlightActions() {
        assertEquals(R.string.dji_account_required_unknown, DjiAccountFlightPolicy.blockingReason(DjiAccountSnapshot())!!.resourceId)
    }

    @Test fun simulatorAndPureUeHilCanExplicitlyBypassRealFlightAccountGate() {
        val loggedOut = DjiAccountSnapshot(DjiAccountState.NOT_LOGGED_IN)
        assertNull(DjiAccountFlightPolicy.blockingReason(loggedOut, requiresAccount = false))
    }

    @Test fun startupPromptOnlyAppearsForMissingOrExpiredLogin() {
        assertTrue(DjiAccountFlightPolicy.shouldPromptAtStartup(DjiAccountSnapshot(DjiAccountState.NOT_LOGGED_IN)))
        assertTrue(DjiAccountFlightPolicy.shouldPromptAtStartup(DjiAccountSnapshot(DjiAccountState.TOKEN_OUT_OF_DATE)))
        assertTrue(!DjiAccountFlightPolicy.shouldPromptAtStartup(DjiAccountSnapshot(DjiAccountState.LOGGED_IN)))
        assertTrue(!DjiAccountFlightPolicy.shouldPromptAtStartup(DjiAccountSnapshot(DjiAccountState.UNKNOWN)))
    }
}
