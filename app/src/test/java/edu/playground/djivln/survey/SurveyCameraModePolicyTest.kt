package edu.playground.djivln.survey

import org.junit.Assert.*
import org.junit.Test

class SurveyCameraModePolicyTest {
    private val mini = DjiCameraProfileCatalog.resolve("DJI Mini 2", "DJI Mini 2 Camera").profile

    @Test fun exactAliasesRejectPrefixCollisionsAndConflictingModels() {
        assertTrue(DjiCameraProfileCatalog.resolve("DJI Mini 2", "DJI Mini 2 Camera").verifiedProfile)
        for (identity in listOf("M300_RTK", "Matrice 300 RTK", "M350_RTK", "MINI2SE", "M3ENTERPRISE", "M4E_UNKNOWN")) {
            assertFalse(identity, DjiCameraProfileCatalog.resolve(identity).verifiedProfile)
        }
        assertFalse(DjiCameraProfileCatalog.resolve("DJI_MINI_2", "DJI_MINI_4_PRO").verifiedProfile)
        assertFalse(DjiCameraProfileCatalog.resolve("DJI_MINI_2", "ZENMUSE_H20").verifiedProfile)
        assertTrue(DjiCameraProfileCatalog.resolve("M4E", "DJI Matrice 4E Camera", "WIDE_CAMERA").verifiedProfile)
        assertTrue(DjiCameraProfileCatalog.resolve("PHANTOM_4_ADVANCED", "P4A").verifiedProfile)
        assertTrue(DjiCameraProfileCatalog.resolve("DJI_MATRICE_4_SERIES", "M4E", "WIDE_CAMERA").verifiedProfile)
        assertTrue(DjiCameraProfileCatalog.resolve("DJI_MAVIC_3_ENTERPRISE_SERIES", "M3T", "WIDE_CAMERA").verifiedProfile)
        assertFalse(DjiCameraProfileCatalog.resolve("DJI_MATRICE_4_SERIES", "M3E", "WIDE_CAMERA").verifiedProfile)
        assertFalse(DjiCameraProfileCatalog.resolve("DJI_MATRICE_4_SERIES", "WIDE_CAMERA").verifiedProfile)
    }

    @Test fun enterpriseRequiresExplicitWideLensAndRejectsOtherSources() {
        for (identity in listOf("M3E", "M3T", "M3TA", "M3M", "M4E", "M4T", "M30_SERIES")) {
            assertFalse(identity, DjiCameraProfileCatalog.resolve(identity).verifiedProfile)
            assertTrue(identity, DjiCameraProfileCatalog.resolve(identity, "WIDE_CAMERA").verifiedProfile)
            for (source in listOf("DEFAULT_CAMERA", "ZOOM_CAMERA", "INFRARED_CAMERA", "MS_G_CAMERA")) {
                assertFalse("$identity/$source", DjiCameraProfileCatalog.resolve(identity, source).verifiedProfile)
            }
        }
        assertTrue(DjiCameraProfileCatalog.resolve("M3M", "RGB_CAMERA").verifiedProfile)
        assertFalse(DjiCameraProfileCatalog.resolve("MAVIC_2_ZOOM").verifiedProfile)
    }

    @Test fun missingOrIncompatibleModeDoesNotAuthorizeGeometry() {
        assertNull(SurveyCameraModePolicy.issue(mini, 4.0 / 3, 12, true, 1.0, true, true, true))
        assertEquals(SurveyCameraModeIssue.PHOTO_RATIO_UNCONFIRMED,
            SurveyCameraModePolicy.issue(mini, null, 12, true, 1.0, true, true, true))
        assertEquals(SurveyCameraModeIssue.PHOTO_RATIO_UNCONFIRMED,
            SurveyCameraModePolicy.issue(mini, 16.0 / 9, 12, true, 1.0, true, true, true))
        for (resolution in listOf(null, 48)) {
            assertEquals(SurveyCameraModeIssue.PHOTO_RESOLUTION_UNCONFIRMED,
                SurveyCameraModePolicy.issue(mini, 4.0 / 3, resolution, true, 1.0, true, true, true))
        }
        for (zoom in listOf(null, 2.0, Double.NaN)) {
            assertEquals(SurveyCameraModeIssue.ZOOM_UNCONFIRMED,
                SurveyCameraModePolicy.issue(mini, 4.0 / 3, 12, true, zoom, true, true, true))
        }
        assertEquals(SurveyCameraModeIssue.ORIENTATION_UNCONFIRMED,
            SurveyCameraModePolicy.issue(mini, 4.0 / 3, 12, true, 1.0, true, false, true))
    }

    @Test fun widescreenCaptureKeepsHorizontalFovButCropsHeight() {
        val cropped = requireNotNull(SurveyCameraModePolicy.captureGeometry(mini, 16.0 / 9.0))
        assertEquals(mini.imageWidthPixels, cropped.imageWidthPixels)
        assertEquals(2250, cropped.imageHeightPixels)
        assertEquals(mini.horizontalFieldOfViewDegrees, cropped.horizontalFieldOfViewDegrees, 0.001)
        assertTrue(cropped.verticalFieldOfViewDegrees < mini.verticalFieldOfViewDegrees)
        assertNull(SurveyCameraModePolicy.issue(cropped, 16.0 / 9.0, 12, true, 1.0, true, true, true))
        assertFalse(SurveyCameraModePolicy.sameGeometry(mini, cropped))
        assertNull(SurveyCameraModePolicy.captureGeometry(mini, null))
        assertNull(SurveyCameraModePolicy.captureGeometry(mini, 1.0))
    }

    @Test fun unchangedAspectDoesNotHideDifferentFovOrPixelDimensions() {
        assertTrue(SurveyCameraModePolicy.sameGeometry(mini, mini))
        assertFalse(SurveyCameraModePolicy.sameGeometry(mini, mini.copy(horizontalFieldOfViewDegrees = 60.0)))
        assertFalse(SurveyCameraModePolicy.sameGeometry(mini, mini.copy(imageWidthPixels = 8000, imageHeightPixels = 6000)))
    }
}
