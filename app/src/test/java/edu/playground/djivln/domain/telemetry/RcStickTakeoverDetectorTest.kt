package edu.playground.djivln.domain.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcStickTakeoverDetectorTest {
    @Test fun ignoresCenterNoiseAndShortSpikes() {
        val detector = RcStickTakeoverDetector()

        assertFalse(detector.update(intArrayOf(60, -70, 40, 0), 1_000_000_000L))
        assertFalse(detector.update(intArrayOf(120, 0, 0, 0), 1_100_000_000L))
        assertFalse(detector.update(intArrayOf(0, 0, 0, 0), 1_200_000_000L))
    }

    @Test fun requiresSustainedDeliberateInputAndReleasesAtCenter() {
        val detector = RcStickTakeoverDetector()

        assertFalse(detector.update(intArrayOf(150, 0, 0, 0), 1_000_000_000L))
        assertFalse(detector.update(intArrayOf(160, 0, 0, 0), 1_200_000_000L))
        assertTrue(detector.update(intArrayOf(170, 0, 0, 0), 1_260_000_000L))
        assertTrue(detector.update(intArrayOf(80, 0, 0, 0), 1_300_000_000L))
        assertFalse(detector.update(intArrayOf(30, 0, 0, 0), 1_310_000_000L))
    }
}
