package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiCameraProfileCatalogTest {
    @Test
    fun `consumer aircraft use android distance capture and enterprise aircraft keep WPML actions`() {
        assertEquals(
            DjiWaylineCaptureStrategy.ANDROID_DISTANCE_TRIGGER,
            DjiCameraProfileCatalog.waylineCaptureStrategy(
                DjiCameraProfileCatalog.resolve("DJI_MINI_4_PRO").profile,
            ),
        )
        assertEquals(
            DjiWaylineCaptureStrategy.WPML_PHOTO_ACTION,
            DjiCameraProfileCatalog.waylineCaptureStrategy(
                DjiCameraProfileCatalog.resolve("M3E", "WIDE_CAMERA").profile,
            ),
        )
    }

    @Test fun resolvesConsumerAndEnterpriseSdkNames() {
        val mini = DjiCameraProfileCatalog.resolve("DJI_MINI_4_PRO", "DJI_MINI_4_PRO")
        val enterprise = DjiCameraProfileCatalog.resolve("DJI_MAVIC_3_ENTERPRISE_SERIES", "M3E", "WIDE_CAMERA")

        assertTrue(mini.verifiedProfile)
        assertEquals(4032, mini.profile.imageWidthPixels)
        assertEquals(3024, mini.profile.imageHeightPixels)
        assertEquals(2.5, mini.profile.minimumCaptureIntervalSeconds, 0.0)
        assertTrue(enterprise.verifiedProfile)
        assertEquals(0.7, enterprise.profile.minimumCaptureIntervalSeconds, 0.0)
    }

    @Test fun unknownPayloadIsExplicitlyUnverified() {
        val unknown = DjiCameraProfileCatalog.resolve("M350_RTK", "ZENMUSE_CUSTOM")

        assertFalse(unknown.verifiedProfile)
        assertEquals(CameraProfile.GENERIC_4_BY_3.id, unknown.profile.id)
    }

    @Test fun sdkSentinelsAreNotShownAsCameraNames() {
        val unknown = DjiCameraProfileCatalog.resolve("NOT_SUPPORTED", "UNKNOWN", "UNRECOGNIZED", null)

        assertFalse(unknown.verifiedProfile)
        assertEquals("Unrecognized camera", unknown.displayName)
    }

    @Test fun m30WideIsVerifiedOnlyWhenTheLensSourceIsExplicit() {
        val wide = DjiCameraProfileCatalog.resolve("M30_SERIES", "M30T", "WIDE_CAMERA")
        val unspecified = DjiCameraProfileCatalog.resolve("M30_SERIES", "M30T", "DEFAULT_CAMERA")

        assertTrue(wide.verifiedProfile)
        assertEquals(4000, wide.profile.imageWidthPixels)
        assertEquals(3000, wide.profile.imageHeightPixels)
        assertFalse(unspecified.verifiedProfile)
    }

    @Test fun m30VariableZoomAndThermalCannotSilentlyUseWideGeometry() {
        val zoom = DjiCameraProfileCatalog.resolve("M30_SERIES", "M30T", "ZOOM_CAMERA")
        val thermal = DjiCameraProfileCatalog.resolve("M30_SERIES", "M30T", "INFRARED_CAMERA")

        assertFalse(zoom.verifiedProfile)
        assertFalse(thermal.verifiedProfile)
        assertEquals(CameraProfile.GENERIC_4_BY_3.id, zoom.profile.id)
        assertEquals(CameraProfile.GENERIC_4_BY_3.id, thermal.profile.id)
    }

    @Test fun m300IsNotMisidentifiedAsTheM30Series() {
        val m300 = DjiCameraProfileCatalog.resolve("M300_RTK", "ZENMUSE_H20T", "WIDE_CAMERA")

        assertFalse(m300.verifiedProfile)
        assertEquals(CameraProfile.GENERIC_4_BY_3.id, m300.profile.id)
    }
}
