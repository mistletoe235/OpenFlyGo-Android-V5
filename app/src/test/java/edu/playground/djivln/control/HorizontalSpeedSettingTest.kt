package edu.playground.djivln.control

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalSpeedSettingTest {
    @Test fun normalizesStoredValuesToSafeRangeAndStep() {
        assertEquals(1.0, HorizontalSpeedSetting.normalize(null), 0.0)
        assertEquals(1.0, HorizontalSpeedSetting.normalize(Double.NaN), 0.0)
        assertEquals(0.2, HorizontalSpeedSetting.normalize(-3.0), 0.0)
        assertEquals(4.0, HorizontalSpeedSetting.normalize(5.0), 0.0)
        assertEquals(1.1, HorizontalSpeedSetting.normalize(1.13), 0.0001)
    }

    @Test fun adjustmentNeverLeavesConfiguredRange() {
        assertEquals(0.2, HorizontalSpeedSetting.adjusted(0.2, -1), 0.0)
        assertEquals(4.0, HorizontalSpeedSetting.adjusted(4.0, 1), 0.0)
        assertEquals(1.1, HorizontalSpeedSetting.adjusted(1.0, 1), 0.0001)
    }
}
