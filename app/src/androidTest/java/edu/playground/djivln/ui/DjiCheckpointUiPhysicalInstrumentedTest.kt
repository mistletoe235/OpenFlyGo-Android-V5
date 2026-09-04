package edu.playground.djivln.ui

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.NextMainActivity
import edu.playground.djivln.R
import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.survey.SurveyExecutionBackend
import edu.playground.djivln.survey.SurveyExecutionCheckpoint
import edu.playground.djivln.survey.SurveyExecutionCheckpointJson
import edu.playground.djivln.survey.SurveyExecutionPhase
import edu.playground.djivln.survey.SurveyExecutionState
import edu.playground.djivln.survey.SurveyMissionJson
import edu.playground.djivln.survey.surveyPasses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device, display-only checkpoint regression. It never sends a flight command. */
@RunWith(AndroidJUnit4::class)
class DjiCheckpointUiPhysicalInstrumentedTest {
    @Test(timeout = 90_000)
    fun disconnectedResumePointIsShownOnItsActualPass() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
        assertTrue(
            "Physical-device execution requires allow_real_device=true",
            emulator || arguments.getString("allow_real_device") == "true",
        )
        assertTrue("This regression must not run while an aircraft is connected", !DjiSdkBootstrap.snapshot().connected)
        val context = inst.targetContext
        val missionPrefs = context.getSharedPreferences("survey_feature", 0)
        val checkpointPrefs = context.getSharedPreferences("survey_checkpoint", 0)
        val missionRaw = requireNotNull(missionPrefs.getString("active_mission", null))
        val mission = SurveyMissionJson.decode(missionRaw)
        val waypointIndex = 44.coerceAtMost(mission.waypoints.lastIndex)
        val previousNew = checkpointPrefs.getString("active_checkpoint", null)
        val previousLegacy = missionPrefs.getString("active_checkpoint", null)
        val checkpoint = SurveyExecutionCheckpoint(
            missionId = mission.id,
            waypointIndex = waypointIndex,
            state = SurveyExecutionState.PAUSED,
            updatedAtEpochMillis = System.currentTimeMillis(),
            executionLegIndex = waypointIndex,
            phase = SurveyExecutionPhase.SURVEY,
            backend = SurveyExecutionBackend.DJI_KMZ,
            djiBreakpoint = WaylineBreakpoint(
                waylineId = 0,
                waypointId = waypointIndex,
                segmentProgress = 0.35,
            ),
        )
        checkpointPrefs.edit()
            .putString("active_checkpoint", SurveyExecutionCheckpointJson.encode(checkpoint))
            .commit()
        var activity: NextMainActivity? = null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(
                inst.uiAutomation.executeShellCommand(
                    "am start -W -n ${context.packageName}/${NextMainActivity::class.java.name}",
                ),
            ).bufferedReader().use { it.readText() }
            SystemClock.sleep(4_000)
            inst.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NextMainActivity>()
                    .first { !it.isDestroyed && !it.isFinishing }
                requireNotNull(activity).findViewById<View>(R.id.survey_open).performClick()
            }
            SystemClock.sleep(3_000)
            val controller = requireNotNull(field(requireNotNull(activity), "surveyController"))
            val displayed = field(controller, "activeDjiRecoveryDisplayCheckpoint") as SurveyExecutionCheckpoint?
            assertNotNull("Checkpoint must be available before aircraft connection", displayed)
            assertEquals(waypointIndex, displayed?.waypointIndex)
            val bestCheckpoint = controller.javaClass
                .getDeclaredMethod("bestAvailableCheckpoint", mission.javaClass)
                .apply { isAccessible = true }
                .invoke(controller, mission) as SurveyExecutionCheckpoint?
            assertEquals(
                "Mission export must use the durable saved checkpoint",
                checkpoint,
                bestCheckpoint,
            )
            val flight = requireNotNull(field(requireNotNull(activity), "flightController"))
            val overlay = field(flight, "pendingExecutionOverlay") as SurveyExecutionOverlay?
            assertNotNull("Survey map must receive an execution overlay", overlay)
            assertNotNull("Resume point marker must have a map coordinate", overlay?.recoveryPoint)
            val expectedPass = mission.surveyPasses()
                .first { waypointIndex in it.firstWaypointIndex..it.lastWaypointIndex }
            assertEquals(expectedPass.waypoints.map { it.point }, overlay?.activeRoutePoints)
            assertEquals(
                context.getString(
                    R.string.dji_resume_breakpoint_marker,
                    waypointIndex + 1,
                    mission.waypoints[waypointIndex].passIndex + 1,
                ),
                overlay?.title,
            )
        } finally {
            checkpointPrefs.edit().apply {
                if (previousNew == null) remove("active_checkpoint") else putString("active_checkpoint", previousNew)
            }.commit()
            missionPrefs.edit().apply {
                if (previousLegacy == null) remove("active_checkpoint") else putString("active_checkpoint", previousLegacy)
            }.commit()
            activity?.let { inst.runOnMainSync { it.finish() } }
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(target)
}
