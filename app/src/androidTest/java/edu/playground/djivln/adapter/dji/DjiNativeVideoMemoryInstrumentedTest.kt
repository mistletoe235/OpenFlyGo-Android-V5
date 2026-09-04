package edu.playground.djivln.adapter.dji

import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dji.v5.lib.codec.VideoCodec
import dji.v5.lib.codec.opengl.GLFrameDispatcher
import dji.v5.manager.datacenter.camera.StreamDecoder
import dji.v5.manager.interfaces.ICameraStreamManager
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.NextMainActivity
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** No aircraft actions. Directly exercises the real DJI codec + GL/ImageReader lifecycle. */
@RunWith(AndroidJUnit4::class)
class DjiNativeVideoMemoryInstrumentedTest {
    @Test(timeout = 480_000)
    fun nativeDecoderAndRgbaListenerLifetime() {
        assertTrue("EMULATOR ONLY", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertFalse(DjiSdkBootstrap.snapshot().connected)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val bytes = File(context.filesDir, "native-probe.h264").readBytes()
        val starts = arrayListOf(0)
        for (i in 1 until bytes.size - 4) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() &&
                bytes[i + 3].toInt() and 31 == 9 && i > 4) starts.add(i)
        }
        starts.add(bytes.size)
        val packets = starts.zipWithNext().map { (a, b) -> bytes.copyOfRange(a, b) }
        assertTrue("Need Annex-B access units", packets.size >= 100)
        context.startActivity(Intent(context, NextMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(4000)
        var activity: NextMainActivity? = null
        lateinit var video: SurfaceView
        inst.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<NextMainActivity>().first { !it.isDestroyed && !it.isFinishing }
            video = SurfaceView(activity!!)
            activity!!.addContentView(video, ViewGroup.LayoutParams(-1, -1))
        }
        val ready = SystemClock.elapsedRealtime()
        while (!video.holder.surface.isValid && SystemClock.elapsedRealtime() - ready < 5000) SystemClock.sleep(50)
        assertTrue(video.holder.surface.isValid)
        val decoder = StreamDecoder("OpenFlyNativeProbe", ICameraStreamManager.MimeType.H264)
        val dispatcher = checkNotNull(decoder.frameDispatcher)
        dispatcher.putOutputSurface(video.holder.surface, video.width, video.height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE.value)
        val rgbaFrames = AtomicLong()
        val rgbaBytes = AtomicLong()
        val listener = GLFrameDispatcher.OnFrameListener { _, _, length, _, _, _ ->
            rgbaFrames.incrementAndGet()
            rgbaBytes.addAndGet(length.toLong())
        }
        val output = File(context.filesDir, "native-video-memory.jsonl")
        output.writeText("")
        var sent = 0L
        var decoded = 0L
        var listenerAdded = false
        var cycles = 0
        val info = VideoCodec.ReceiveInfo()
        fun memory(phase: String, stage: String) {
            val mi = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val rt = Runtime.getRuntime()
            val row = JSONObject().apply {
                put("phase", phase); put("stage", stage); put("elapsed_ms", SystemClock.elapsedRealtime())
                put("native_bytes", Debug.getNativeHeapAllocatedSize()); put("pss_kb", mi.totalPss)
                put("java_bytes", rt.totalMemory() - rt.freeMemory()); put("sent", sent); put("decoded", decoded)
                put("rgba_frames", rgbaFrames.get()); put("rgba_bytes", rgbaBytes.get()); put("listener_cycles", cycles)
                put("threads", File("/proc/self/task").list()?.size)
                put("decoder", decoder.mediaFormat.toString())
            }
            output.appendText(row.toString() + "\n")
            Log.i("OpenFlyNativeProbe", row.toString())
        }
        val duration = InstrumentationRegistry.getArguments().getString("phase_seconds")?.toIntOrNull()?.coerceIn(10, 120) ?: 40
        try {
            for (phase in listOf("preview_only", "continuous_rgba", "churn_rgba", "preview_after_churn")) {
                if (listenerAdded) { dispatcher.removeOnFrameListener(listener); listenerAdded = false }
                if (phase == "continuous_rgba") {
                    dispatcher.addOnFrameListener(ICameraStreamManager.FrameFormat.RGBA_8888.value, listener)
                    listenerAdded = true
                }
                Runtime.getRuntime().gc(); SystemClock.sleep(250)
                memory(phase, "begin")
                val start = SystemClock.elapsedRealtime()
                var nextSample = start + 10_000
                var nextToggle = start
                while (SystemClock.elapsedRealtime() - start < duration * 1000L) {
                    val t = SystemClock.elapsedRealtime()
                    assertFalse(DjiSdkBootstrap.snapshot().connected)
                    if (phase == "churn_rgba" && t >= nextToggle) {
                        if (listenerAdded) dispatcher.removeOnFrameListener(listener)
                        else {
                            dispatcher.addOnFrameListener(ICameraStreamManager.FrameFormat.RGBA_8888.value, listener)
                            cycles++
                        }
                        listenerAdded = !listenerAdded
                        nextToggle = t + 250
                    }
                    val packet = packets[(sent % packets.size).toInt()]
                    decoder.sendFrame(packet, 0, packet.size)
                    sent++
                    repeat(4) { if (decoder.receiveFrame(info)) decoded++ }
                    if (SystemClock.elapsedRealtime() >= nextSample) {
                        memory(phase, "running"); nextSample += 10_000
                    }
                    SystemClock.sleep((33 - (SystemClock.elapsedRealtime() - t)).coerceAtLeast(1))
                }
                Runtime.getRuntime().gc(); SystemClock.sleep(250)
                memory(phase, "post_gc")
                assertTrue("DJI decoder produced no frames", decoded > 20)
                if (phase == "continuous_rgba") assertTrue("No real SDK RGBA output", rgbaFrames.get() > 10)
            }
        } finally {
            if (listenerAdded) dispatcher.removeOnFrameListener(listener)
            decoder.release()
            SystemClock.sleep(1000)
            Runtime.getRuntime().gc()
            memory("released", "post_gc")
            inst.runOnMainSync {
                (video.parent as? ViewGroup)?.removeView(video)
                activity?.finish()
            }
        }
    }
}
