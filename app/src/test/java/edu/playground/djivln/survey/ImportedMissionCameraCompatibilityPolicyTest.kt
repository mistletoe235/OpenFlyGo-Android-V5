package edu.playground.djivln.survey

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedMissionCameraCompatibilityPolicyTest {
    private val mission by lazy {
        val directory = fixtureDirectory()
        val manifest = org.json.JSONObject(
            File(directory, "openfly-active-recapture-two-buildings-v30-manifest.json").readText(),
        )
        val filename = manifest.getJSONArray("sorties").getJSONObject(0).getString("file")
        SurveyMissionJson.decode(
            File(directory, filename).readText(),
        )
    }

    @Test
    fun `V30 accepts verified 4 by 3 camera with different profile identity`() {
        val currentCamera = mission.cameraProfile.copy(
            id = "dji-mini-4-pro-photo-12mp",
            imageWidthPixels = 4032,
            imageHeightPixels = 3024,
            minimumCaptureIntervalSeconds = 2.5,
        )

        val result = ImportedMissionCameraCompatibilityPolicy.evaluate(
            mission = mission,
            currentCamera = currentCamera,
            cameraConnected = true,
            profileVerified = true,
            requireKmzPayload = true,
            payloadPositionSupported = true,
            payloadLensSupported = true,
        )

        assertTrue(result.reasons.joinToString(), result.compatible)
    }

    @Test
    fun `V30 reports all incompatible camera conditions together`() {
        val result = ImportedMissionCameraCompatibilityPolicy.evaluate(
            mission = mission,
            currentCamera = mission.cameraProfile.copy(
                imageWidthPixels = 4000,
                imageHeightPixels = 2250,
                minimumCaptureIntervalSeconds = 5.0,
            ),
            cameraConnected = false,
            profileVerified = false,
            requireKmzPayload = true,
            payloadPositionSupported = false,
            payloadLensSupported = false,
        )

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("disconnected") })
        assertTrue(result.warnings.any { it.contains("estimated") })
        assertTrue(result.warnings.any { it.contains("aspect ratio") })
        assertTrue(result.reasons.any { it.contains("minimum capture interval") })
        assertTrue(result.reasons.any { it.contains("payload") })
        assertTrue(result.reasons.any { it.contains("capture lens") })
    }

    @Test fun unlistedCameraAndGeometryMismatchDoNotBlockImportedMission() {
        val result = ImportedMissionCameraCompatibilityPolicy.evaluate(
            mission = mission,
            currentCamera = mission.cameraProfile.copy(id = "unlisted-camera", imageHeightPixels = 2250),
            cameraConnected = true,
            profileVerified = false,
            requireKmzPayload = true,
            payloadPositionSupported = true,
            payloadLensSupported = true,
        )
        assertTrue(result.reasons.joinToString(), result.compatible)
        assertTrue(result.warnings.isNotEmpty())
    }

    private fun fixtureDirectory(): File {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(root, "testdata/active-recapture/two-buildings"),
            File(requireNotNull(root.parentFile), "testdata/active-recapture/two-buildings"),
        ).first { File(it, "openfly-active-recapture-two-buildings-v30-manifest.json").isFile }
    }
}
