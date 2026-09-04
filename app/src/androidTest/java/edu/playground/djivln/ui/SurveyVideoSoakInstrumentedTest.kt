package edu.playground.djivln.ui

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.ux.map.MapWidget
import dji.v5.ux.mapkit.core.models.DJILatLng
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.NextMainActivity
import edu.playground.djivln.adapter.dji.DjiV5CameraPreviewPort
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.AircraftTelemetrySource
import edu.playground.djivln.domain.telemetry.AttitudeDegrees
import edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond
import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.domain.wayline.WaylineState
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.SurveyCollectionMode
import edu.playground.djivln.survey.SurveyConstraints
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.SurveyPlanner
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emulator-only diagnostic. Uses production map/mission UI and a looping 1440x1080
 * H264 MediaPlayer on the production TextureView. It does NOT simulate DJI radio,
 * aircraft firmware, DJI's native decoder, photo capture, or reconstruction upload.
 * Never calls takeoff, Virtual Stick, upload/start/resume mission or camera actions.
 * Long-track seeding compresses accumulated flight history, not elapsed wall time.
 */
@RunWith(AndroidJUnit4::class)
class SurveyVideoSoakInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output by lazy { File(context.filesDir, "survey-video-soak.jsonl") }
    private val videoFrames = AtomicLong()

    @Test(timeout = 660_000)
    fun compareShortAndLongTracksWithContinuousVideo() {
        assertTrue("EMULATOR ONLY", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertFalse("Must not connect an aircraft", DjiSdkBootstrap.snapshot().connected)
        output.writeText("")
        context.startActivity(Intent(context, NextMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // AppCompat may recreate the first instance to apply the persisted locale.
        SystemClock.sleep(3000)
        var resumed: NextMainActivity? = null
        val started = SystemClock.elapsedRealtime()
        while (resumed == null && SystemClock.elapsedRealtime() - started < 30_000) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NextMainActivity>().firstOrNull {
                        !it.isDestroyed && !it.isFinishing && get(it, "flightController") != null
                    }?.let { resumed = it }
            }
            SystemClock.sleep(100)
        }
        val activity = checkNotNull(resumed) { "Main activity not resumed" }
        instrumentation.runOnMainSync { set(activity, "accountStartupPromptHandled", true) }
        dismissOptionalLogin(activity)
        lateinit var flight: FlightFeatureController
        lateinit var survey: SurveyFeatureController
        lateinit var widget: MapWidget
        val mission = SurveyPlanner.plan("EMULATOR SOAK - NO FLIGHT", listOf(
            GeoPoint(31.0, 121.0), GeoPoint(31.0, 121.004),
            GeoPoint(31.003, 121.004), GeoPoint(31.003, 121.0)),
            constraints = SurveyConstraints(altitudeMetersAgl = 40.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION))
        instrumentation.runOnMainSync {
            (get(activity, "telemetrySource") as AircraftTelemetrySource).stop()
            val feature = NextMainActivity::class.java.declaredClasses.first { it.simpleName == "Feature" }
            val surveyFeature = feature.enumConstants.first { it.toString() == "SURVEY" }
            invoke(activity, "showOverlay", surveyFeature)
            flight = get(activity, "flightController") as FlightFeatureController
            survey = get(activity, "surveyController") as SurveyFeatureController
            invoke(survey, "activateMission", mission, "EMULATOR SOAK / SYNTHETIC GPS", false, false, false, false)
            widget = get(flight, "mapWidget") as MapWidget
        }
        val mapStart = SystemClock.elapsedRealtime()
        while (get(flight, "map") == null && SystemClock.elapsedRealtime() - mapStart < 30_000) SystemClock.sleep(100)
        check(get(flight, "map") != null) { "Real map not initialized" }
        var overlayEnabled = true
        val editor = get(survey, "mapEditor") as SurveyMapEditor
        val proxy = Proxy.newProxyInstance(SurveyMapEditor::class.java.classLoader,
            arrayOf(SurveyMapEditor::class.java)) { _, method, args ->
            if (method.name == "renderExecutionOverlay" && !overlayEnabled) null
            else method.invoke(editor, *(args ?: emptyArray()))
        }
        var player: MediaPlayer? = null
        val frames = ArrayList<Double>()
        var lastFrameNanos = 0L
        var framesRunning = true
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!framesRunning) return
                if (lastFrameNanos > 0 && frames.size < 20_000) frames.add((time - lastFrameNanos) / 1e6)
                lastFrameNanos = time
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        try {
            instrumentation.runOnMainSync {
                set(survey, "mapEditor", proxy)
                val state = snapshot(0)
                set(activity, "aircraftSnapshot", state)
                flight.render(state, "EMULATOR SOAK - SYNTHETIC GPS")
            }
            SystemClock.sleep(1000)
            instrumentation.runOnMainSync {
                val port = get(flight, "previewPort") as DjiV5CameraPreviewPort
                port.unbind()
                val texture = activity.findViewById<TextureView>(edu.playground.djivln.R.id.camera_preview)
                val original = checkNotNull(texture.surfaceTextureListener)
                texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = original.onSurfaceTextureAvailable(t, w, h)
                    override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = original.onSurfaceTextureSizeChanged(t, w, h)
                    override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = original.onSurfaceTextureDestroyed(t)
                    override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
                        videoFrames.incrementAndGet()
                        original.onSurfaceTextureUpdated(t)
                    }
                }
                // The synthetic player supplies frames; retain the production PIP freshness logic.
                set(port, "receivedStreamBytes", 1L)
                val surface = get(flight, "previewSurface") as Surface
                player = MediaPlayer().apply {
                    setDataSource(File(context.filesDir, "soak-video.mp4").absolutePath)
                    setSurface(surface)
                    isLooping = true
                    prepare()
                    start()
                }
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
            val seconds = InstrumentationRegistry.getArguments().getString("phase_seconds")?.toIntOrNull()?.coerceIn(10, 90) ?: 60
            val phases = listOf(
                Triple("short_live", 0, true), Triple("long_live", 20_000, true),
                Triple("long_static_overlay", 20_000, false), Triple("short_static_overlay", 0, false),
                Triple("short_live_repeat", 0, true),
            )
            var tick = 0
            for ((name, initialPoints, liveOverlay) in phases) {
                assertFalse("Unexpected aircraft connection", DjiSdkBootstrap.snapshot().connected)
                instrumentation.runOnMainSync {
                    overlayEnabled = liveOverlay
                    widget.clearFlightPath()
                    set(widget, "flightPathPoints", ArrayList<DJILatLng>().apply {
                        repeat(initialPoints) { index ->
                            val p = position(index)
                            add(DJILatLng(p.latitude, p.longitude))
                        }
                    })
                    frames.clear()
                    lastFrameNanos = 0
                }
                Runtime.getRuntime().gc()
                SystemClock.sleep(250)
                sample(name, "begin", widget, flight, player, frames, emptyList())
                val costs = ArrayList<Double>()
                val mapCosts = ArrayList<Double>()
                val uiCosts = ArrayList<Double>()
                val begin = SystemClock.elapsedRealtime()
                var nextSample = begin + 15_000
                while (SystemClock.elapsedRealtime() - begin < seconds * 1000L) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    instrumentation.runOnMainSync {
                        val state = snapshot(tick)
                        set(activity, "aircraftSnapshot", state)
                        set(survey, "waylineState", WaylineState(phase = WaylinePhase.EXECUTING,
                            missionFileName = "EMULATOR_SOAK", waylineId = 0,
                            waypointIndex = (tick / 100) % mission.waypoints.size))
                        val mapBegin = SystemClock.elapsedRealtimeNanos()
                        val p = state.aircraftLocation!!
                        invoke(widget, "updateAircraftLocation", LocationCoordinate3D(p.latitude, p.longitude, 40.0))
                        mapCosts.add((SystemClock.elapsedRealtimeNanos() - mapBegin) / 1e6)
                        val uiBegin = SystemClock.elapsedRealtimeNanos()
                        flight.render(state, "EMULATOR SOAK - SYNTHETIC GPS")
                        survey.refreshLiveState()
                        uiCosts.add((SystemClock.elapsedRealtimeNanos() - uiBegin) / 1e6)
                    }
                    costs.add((SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    tick++
                    if (SystemClock.elapsedRealtime() >= nextSample) {
                        sample(name, "running", widget, flight, player, frames, costs, mapCosts, uiCosts)
                        nextSample += 15_000
                    }
                    SystemClock.sleep((50 - (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).coerceAtLeast(1))
                }
                Runtime.getRuntime().gc()
                SystemClock.sleep(250)
                sample(name, "end_post_gc", widget, flight, player, frames, costs, mapCosts, uiCosts)
            }
            assertTrue("Video must remain playing", player?.isPlaying == true)
        } finally {
            instrumentation.runOnMainSync {
                framesRunning = false
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                player?.release()
                set(survey, "mapEditor", editor)
                set(survey, "waylineState", WaylineState())
                set(activity, "aircraftSnapshot", AircraftSnapshot())
                activity.finish()
            }
        }
    }

    private fun position(tick: Int): GeoPoint {
        val a = tick * 0.005
        return GeoPoint(31.0015 + sin(a) * 0.0012, 121.002 + cos(a) * 0.0016, 40.0)
    }

    private fun snapshot(tick: Int): AircraftSnapshot {
        val p = position(tick)
        val now = SystemClock.elapsedRealtimeNanos()
        return AircraftSnapshot(connected = true, simulatorActive = true, isFlying = true, motorsOn = true,
            aircraftLocation = edu.playground.djivln.domain.telemetry.GeoPoint(p.latitude, p.longitude, 40.0),
            aircraftLocationUpdatedAtNanos = now, relativeAltitudeMeters = 40.0,
            velocity = VelocityMetersPerSecond(3.0, 2.0, 0.0), attitude = AttitudeDegrees(0.0, 0.0, 37.0),
            headingDegrees = 37.0, gimbalPitchDegrees = -90.0, aircraftBatteryPercent = 80,
            remoteControllerBatteryPercent = 80, remoteControllerSignalPercent = 100,
            gpsSatelliteCount = 30, flightMode = "F-WP", updatedAtNanos = now, flightStateUpdatedAtNanos = now)
    }

    private fun sample(phase: String, stage: String, widget: MapWidget, flight: Any, player: MediaPlayer?,
                       frames: List<Double>, costs: List<Double>, mapCosts: List<Double> = emptyList(), uiCosts: List<Double> = emptyList()) {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        var points = 0
        var markers = 0
        var frameSnapshot = emptyList<Double>()
        instrumentation.runOnMainSync {
            points = (get(widget, "flightPathPoints") as List<*>).size
            markers = (get(get(flight, "map")!!, "markerMap") as Map<*, *>).size
            frameSnapshot = frames.toList()
        }
        fun p95(values: List<Double>) = values.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * .95).toInt()] }
        val row = JSONObject().apply {
            put("phase", phase); put("stage", stage); put("elapsed_ms", SystemClock.elapsedRealtime())
            put("pss_kb", info.totalPss); put("native_alloc_bytes", Debug.getNativeHeapAllocatedSize())
            put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            put("graphics_kb", info.memoryStats["summary.graphics"]); put("track_points", points); put("markers", markers)
            put("video_playing", player?.isPlaying); put("video_position_ms", player?.currentPosition)
            put("video_rendered_frames", videoFrames.get())
            put("frame_count", frameSnapshot.size); put("frame_p95_ms", p95(frameSnapshot))
            put("frame_over_50ms", frameSnapshot.count { it > 50 })
            put("tick_count", costs.size); put("tick_p95_ms", p95(costs))
            put("map_p95_ms", p95(mapCosts)); put("ui_p95_ms", p95(uiCosts))
            put("gc_count", Debug.getRuntimeStat("art.gc.gc-count"))
            put("bytes_allocated", Debug.getRuntimeStat("art.gc.bytes-allocated"))
        }
        output.appendText(row.toString() + "\n")
        Log.i("OpenFlySoak", row.toString())
    }

    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun dismissOptionalLogin(activity: Activity) {
        val label = activity.getString(edu.playground.djivln.R.string.action_not_now)
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString() == label && node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            for (i in 0 until node.childCount) if (visit(node.getChild(i))) return true
            return false
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(target, *args)
}
