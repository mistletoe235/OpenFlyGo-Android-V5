package edu.playground.djivln.ui

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.NextMainActivity
import edu.playground.djivln.R
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.AircraftTelemetrySource
import edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond
import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.domain.wayline.WaylineState
import edu.playground.djivln.survey.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Display-only emulator test. No aircraft connection or mission/control commands. */
@RunWith(AndroidJUnit4::class)
class SurveyEtaUiInstrumentedTest {
    @Test(timeout = 90_000)
    fun bothScreensShareProgressPauseRecoveryAndCompletion() {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertFalse(DjiSdkBootstrap.snapshot().connected)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val output = File(context.filesDir, "survey-eta-ui.jsonl").apply { writeText("") }
        context.startActivity(Intent(context, NextMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(4000)
        lateinit var activity: NextMainActivity
        lateinit var survey: SurveyFeatureController
        lateinit var flight: FlightFeatureController
        val mission = SurveyPlanner.plan("ETA UI REGRESSION", listOf(
            GeoPoint(31.0, 121.0), GeoPoint(31.0, 121.001),
            GeoPoint(31.009, 121.001), GeoPoint(31.009, 121.0)),
            constraints = SurveyConstraints(altitudeMetersAgl = 40.0, speedMetersPerSecond = 8.0,
                startPointMode = SurveyStartPointMode.FIRST_ROUTE_START))
        val a = mission.waypoints[0].point
        val b = mission.waypoints[1].point
        fun point(f: Double) = GeoPoint(a.latitude + (b.latitude - a.latitude) * f,
            a.longitude + (b.longitude - a.longitude) * f, a.altitudeMeters)
        fun showSurvey() {
            val type = NextMainActivity::class.java.declaredClasses.first { it.simpleName == "Feature" }
            invoke(activity, "showOverlay", type.enumConstants.first { it.toString() == "SURVEY" })
        }
        inst.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<NextMainActivity>().first { !it.isDestroyed && !it.isFinishing }
            (get(activity, "telemetrySource") as AircraftTelemetrySource).stop()
            set(activity, "accountStartupPromptHandled", true)
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                view.findViewById<Button>(android.R.id.button2)?.takeIf {
                    it.text.toString() == activity.getString(R.string.action_not_now)
                }?.performClick()
            }
            showSurvey()
            survey = get(activity, "surveyController") as SurveyFeatureController
            flight = get(activity, "flightController") as FlightFeatureController
            invoke(survey, "activateMission", mission, "ETA UI TEST / NO FLIGHT", false, false, false, false)
        }
        fun render(phase: WaylinePhase, index: Int, p: GeoPoint, speed: Double = 8.0,
                   breakpoint: WaylineBreakpoint? = null): SurveyEtaSnapshot {
            lateinit var result: SurveyEtaSnapshot
            inst.runOnMainSync {
                set(activity, "aircraftSnapshot", AircraftSnapshot(simulatorActive = true,
                    aircraftLocation = edu.playground.djivln.domain.telemetry.GeoPoint(p.latitude, p.longitude, p.altitudeMeters),
                    relativeAltitudeMeters = p.altitudeMeters, velocity = VelocityMetersPerSecond(speed, 0.0, 0.0)))
                set(survey, "waylineState", WaylineState(phase = phase, missionFileName = mission.id,
                    waylineId = 0, waypointIndex = index, breakpoint = breakpoint))
                invoke(survey, "renderEta", mission, SurveyExecutionBackend.DJI_KMZ)
                result = get(flight, "surveyEtaSnapshot") as SurveyEtaSnapshot
                val compact = activity.findViewById<TextView>(R.id.survey_eta).text.toString()
                val expanded = activity.findViewById<TextView>(R.id.survey_eta_time).text.toString()
                if (result.phase in setOf(SurveyEtaPhase.REMAINING, SurveyEtaPhase.PAUSED)) {
                    val time = Regex("\\d+:\\d{2}")
                    assertEquals(time.findAll(expanded).last().value, time.find(compact)?.value)
                }
                output.appendText(JSONObject().apply {
                    put("phase", phase.name); put("index", index); put("seconds", result.estimate.totalSeconds)
                    put("compact", compact); put("expanded", expanded)
                }.toString() + "\n")
            }
            return result
        }
        try {
            assertEquals(SurveyEtaPhase.PLANNED, render(WaylinePhase.IDLE, 0, a).phase)
            val start = render(WaylinePhase.EXECUTING, 0, a)
            val middle = render(WaylinePhase.EXECUTING, 0, point(.5))
            assertTrue(middle.estimate.totalSeconds < start.estimate.totalSeconds)
            val paused = render(WaylinePhase.PAUSED, 0, point(.5), 0.0)
            assertEquals(SurveyEtaPhase.PAUSED, paused.phase)
            assertEquals(paused, render(WaylinePhase.PAUSED, 0, point(.5), 0.0))
            val recovery = render(WaylinePhase.RECOVERING, 0, a, 0.0, WaylineBreakpoint(0, 0, .5))
            assertTrue(recovery.estimate.totalSeconds > middle.estimate.totalSeconds)
            val resumed = render(WaylinePhase.EXECUTING, 0, point(.5))
            assertEquals(middle.estimate.totalSeconds, resumed.estimate.totalSeconds, 0.001)
            val before = render(WaylinePhase.EXECUTING, 0, point(.9999), .1)
            val after = render(WaylinePhase.EXECUTING, 1, b, 0.0)
            assertTrue(kotlin.math.abs(before.estimate.totalSeconds - after.estimate.totalSeconds) < 5.0)
            inst.runOnMainSync { invoke(activity, "closeOverlay"); showSurvey() }
            assertEquals(after.estimate, render(WaylinePhase.EXECUTING, 1, b, 0.0).estimate)
            assertEquals(SurveyEtaPhase.COMPLETED,
                render(WaylinePhase.FINISHED, mission.waypoints.lastIndex, mission.waypoints.last().point).phase)
        } finally {
            inst.runOnMainSync {
                set(survey, "waylineState", WaylineState())
                set(activity, "aircraftSnapshot", AircraftSnapshot())
                activity.finish()
            }
        }
    }

    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(target, *args)
}
