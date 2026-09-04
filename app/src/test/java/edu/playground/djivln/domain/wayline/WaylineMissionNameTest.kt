package edu.playground.djivln.domain.wayline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaylineMissionNameTest {
    @Test fun stripsKmzSuffixBeforeSdkCalls() {
        assertEquals("survey-001", WaylineMissionName.normalize("survey-001.kmz"))
        assertEquals("survey-001", WaylineMissionName.normalize("survey-001.KMZ"))
    }

    @Test fun stripsDirectoryButPreservesOtherDots() {
        assertEquals("survey.v2", WaylineMissionName.normalize("/tmp/waylines/survey.v2.kmz"))
        assertEquals("survey.v2", WaylineMissionName.normalize("C:\\waylines\\survey.v2.KMZ"))
    }

    @Test fun matchingAcceptsSdkAndLocalRepresentations() {
        assertTrue(WaylineMissionName.matches("survey-001", "survey-001.kmz"))
        assertFalse(WaylineMissionName.matches("survey-001", "survey-002.kmz"))
        assertFalse(WaylineMissionName.matches(null, "survey-001.kmz"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyMissionName() {
        WaylineMissionName.normalize(".kmz")
    }

    @Test fun nullableNormalizationIgnoresEmptySdkValues() {
        assertNull(WaylineMissionName.normalizeOrNull(null))
        assertNull(WaylineMissionName.normalizeOrNull("  "))
        assertNull(WaylineMissionName.normalizeOrNull(".kmz"))
        assertFalse(WaylineMissionName.matches("survey", ""))
    }
}
