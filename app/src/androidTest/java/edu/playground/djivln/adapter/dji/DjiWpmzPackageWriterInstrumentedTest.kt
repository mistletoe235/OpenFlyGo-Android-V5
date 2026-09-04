package edu.playground.djivln.adapter.dji

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dji.sdk.wpmz.value.mission.CameraLensType
import edu.playground.djivln.survey.CaptureAction
import edu.playground.djivln.survey.DjiCameraProfileCatalog
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.SurveyRegressionMissionFactory
import edu.playground.djivln.survey.SurveyWaypoint
import edu.playground.djivln.survey.SurveyWaypointKind
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DjiWpmzPackageWriterInstrumentedTest {
    @Test
    fun generatedKmzContainsExecutableDistancePhotoAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "wpmz-contract-test.kmz").also { it.delete() }
        val mission = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        ).copy(cameraProfile = DjiCameraProfileCatalog.resolve("M3E").profile)

        val validation = DjiWpmzPackageWriter(context).generate(
            source = mission,
            destination = destination,
            globalRthHeightMeters = 80.0,
            payloadPositionIndex = 0,
            payloadLensType = CameraLensType.WIDE,
        )

        assertTrue(validation.details, validation.valid)
        val wpml = ZipFile(destination).use { zip ->
            zip.getInputStream(zip.getEntry("wpmz/waylines.wpml")).bufferedReader().use { it.readText() }
        }
        assertTrue(wpml.contains("<wpml:actionTriggerType>multipleDistance</wpml:actionTriggerType>"))
        assertTrue(wpml.contains("<wpml:actionTriggerParam>"))
        assertTrue(wpml.contains("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>"))
        assertTrue(wpml.contains("<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>"))
        assertTrue(wpml.contains("<wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>"))
        assertTrue(
            Regex("<wpml:gimbalPitchRotateAngle>-90(?:\\.0+)?</wpml:gimbalPitchRotateAngle>").containsMatchIn(wpml),
        )
        assertTrue(wpml.contains("<wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>"))
        assertTrue(wpml.contains("<wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>"))
        assertTrue(wpml.contains("<wpml:waypointGimbalHeadingMode>fixed</wpml:waypointGimbalHeadingMode>"))
    }

    @Test
    fun generatedLargeKmzContainsMultipleBoundedWaylines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "wpmz-multi-wayline-test.kmz").also { it.delete() }
        val base = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )
        val waypoints = List(1_022) { index ->
            SurveyWaypoint(
                point = GeoPoint(
                    latitude = 31.025 + index * 0.0000001,
                    longitude = 121.435 + index * 0.0000001,
                    altitudeMeters = 30.0,
                ),
                headingDegrees = 0.0,
                gimbalPitchDegrees = -90.0,
                kind = SurveyWaypointKind.CAPTURE_POINT,
                captureAction = CaptureAction.CAPTURE_ON_REACH,
                passIndex = index,
            )
        }
        val mission = base.copy(
            id = "wpmz-multi-wayline-test",
            name = "WPMZ multi-wayline test",
            waypoints = waypoints,
            estimatedPathMeters = 150.0,
            estimatedPhotoCount = waypoints.size,
            estimatedFlightSeconds = 75.0,
        )

        val validation = DjiWpmzPackageWriter(context).generate(
            source = mission,
            destination = destination,
            globalRthHeightMeters = 80.0,
            payloadPositionIndex = 0,
            payloadLensType = CameraLensType.WIDE,
        )

        assertTrue(validation.details, validation.valid)
        val wpml = ZipFile(destination).use { zip ->
            zip.getInputStream(zip.getEntry("wpmz/waylines.wpml")).bufferedReader().use { it.readText() }
        }
        assertTrue(wpml.contains("<wpml:finishAction>noAction</wpml:finishAction>"))
        assertTrue(!wpml.contains("<wpml:finishAction>goHome</wpml:finishAction>"))
        val folders = Regex("<Folder>.*?</Folder>", RegexOption.DOT_MATCHES_ALL).findAll(wpml).map { it.value }.toList()
        assertEquals(6, folders.size)
        assertEquals((0..5).toList(), folders.map { folder ->
            Regex("<wpml:waylineId>(\\d+)</wpml:waylineId>").find(folder)!!.groupValues[1].toInt()
        })
        assertEquals(listOf(190, 190, 190, 190, 190, 72), folders.map { folder ->
            Regex("<wpml:index>(\\d+)</wpml:index>").findAll(folder).count()
        })
        folders.forEach { folder ->
            val indices = Regex("<wpml:index>(\\d+)</wpml:index>")
                .findAll(folder)
                .map { it.groupValues[1].toInt() }
                .toList()
            assertEquals(indices.indices.toList(), indices)
        }
    }
}
