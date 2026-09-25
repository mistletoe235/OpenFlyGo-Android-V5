package edu.playground.djivln.camera

import edu.playground.djivln.ui.SurveyFeatureController
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneCaptureDefaultsTest {
    @Test fun phoneCopiesRequireExplicitOptIn() {
        assertFalse(SurveyFeatureController.DEFAULT_SAVE_TRIGGER_FRAMES)
    }
}
