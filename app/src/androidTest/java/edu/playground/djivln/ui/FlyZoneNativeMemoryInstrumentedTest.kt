package edu.playground.djivln.ui

import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.Polygon
import com.baidu.mapapi.map.OverlayUtil
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.manager.aircraft.flysafe.info.FlyZoneCategory
import dji.v5.manager.aircraft.flysafe.info.FlyZoneInformation
import dji.v5.manager.aircraft.flysafe.info.FlyZoneShape
import dji.v5.manager.aircraft.flysafe.info.MultiPolygonFlyZoneInformation
import dji.v5.manager.aircraft.flysafe.info.MultiPolygonFlyZoneShape
import dji.v5.ux.map.FlyZoneMapHelper
import dji.v5.ux.mapkit.core.maps.DJIMap
import edu.playground.djivln.DjiSdkBootstrap
import edu.playground.djivln.NextMainActivity
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.sin

/** Display-only synthetic zones. Never queries/unlocks/changes aircraft fences. */
@RunWith(AndroidJUnit4::class)
class FlyZoneNativeMemoryInstrumentedTest {
    @Test(timeout = 300_000)
    fun repeatedZoneRefreshMustReleaseAllCategories() {
        val arguments = InstrumentationRegistry.getArguments()
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
        assertTrue(
            "Physical-device execution requires an explicit allow_real_device=true argument",
            emulator || arguments.getString("allow_real_device") == "true",
        )
        assertFalse(DjiSdkBootstrap.snapshot().connected)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val verifyFixed = arguments.getString("verify_fixed") == "true"
        val mutateGeometry = arguments.getString("mutate_geometry") == "true"
        val duplicateIds = arguments.getString("duplicate_ids") == "true"
        if (arguments.getString("legacy_overlay") == "true") {
            OverlayUtil.setOverlayUpgrade(false)
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            inst.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${NextMainActivity::class.java.name}",
            ),
        ).bufferedReader().use { it.readText() }
        SystemClock.sleep(4000)
        lateinit var activity: NextMainActivity
        lateinit var helper: FlyZoneMapHelper
        lateinit var map: BaiduMap
        inst.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<NextMainActivity>()
                .first { !it.isDestroyed && !it.isFinishing }
            val flight = get(activity, "flightController")!!
            val widget = get(flight, "mapWidget")!!
            helper = get(widget, "flyZoneHelper") as FlyZoneMapHelper
            map = (get(flight, "map") as DJIMap).map as BaiduMap
        }
        val output = File(context.filesDir, "flyzone-native-memory.jsonl")
        output.writeText("")
        var polygons = 0
        fun currentPolygons(): Set<Polygon> {
            val unique = Collections.newSetFromMap(IdentityHashMap<Polygon, Boolean>())
            for (field in BaiduMap::class.java.declaredFields) {
                if (!List::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                (field.get(map) as? List<*>)?.filterIsInstance<Polygon>()?.let(unique::addAll)
            }
            return unique
        }
        fun closeOwnedObjects(values: Set<Polygon>) {
            // Diagnostic counterfactual only: prove whether Polygon.remove() releases
            // its Java-held native child wrappers. Not a production compatibility fix.
            for (polygon in values) {
                for (field in Polygon::class.java.declaredFields) {
                    if (!AutoCloseable::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    (field.get(polygon) as? AutoCloseable)?.close()
                }
            }
        }
        fun sample(category: String, count: Int, stage: String) {
            inst.runOnMainSync {
                polygons = currentPolygons().size
            }
            Runtime.getRuntime().gc(); SystemClock.sleep(150)
            val rt = Runtime.getRuntime()
            val row = JSONObject().apply {
                put("category", category); put("refreshes", count); put("stage", stage)
                put("polygons", polygons); put("native_bytes", Debug.getNativeHeapAllocatedSize())
                put("java_bytes", rt.totalMemory() - rt.freeMemory())
                put("elapsed_ms", SystemClock.elapsedRealtime())
            }
            output.appendText(row.toString() + "\n")
            android.util.Log.i("OpenFlyZoneProbe", row.toString())
        }
        try {
            val cases = if (verifyFixed) FlyZoneCategory.values().map { Triple(it, false, false) } else listOf(
                Triple(FlyZoneCategory.RESTRICTED, false, false),
                Triple(FlyZoneCategory.UTMISS_REGULATION, false, false),
                Triple(FlyZoneCategory.UTMISS_LAW_ALLOW, false, false),
                Triple(FlyZoneCategory.UTMISS_REGULATION, true, false),
                Triple(FlyZoneCategory.RESTRICTED, false, true),
            )
            if (verifyFixed) assertFalse("The production provider must select the non-leaking renderer", OverlayUtil.isOverlayUpgrade())
            for ((category, deduplicate, closeChildren) in cases) {
                val label = category.name + if (deduplicate) "_DEDUP_CONTROL" else if (closeChildren) "_CLOSE_CHILDREN_CONTROL" else ""
                inst.runOnMainSync { helper.onFlyZoneListUpdate(emptyList()); map.clear() }
                val zones = listOf(FlyZoneInformation.Builder.newBuilder()
                    .flyZoneID(999001).coordinate(LocationCoordinate2D(31.0, 121.0)).radius(200.0)
                    .name("EMULATOR MEMORY PROBE").shape(FlyZoneShape.MULTI_POLYGON).category(category)
                    .subFlyZones((0 until 10).map { index ->
                        MultiPolygonFlyZoneInformation.Builder.newBuilder().areaID(if (duplicateIds) 1 else index + 1)
                            .shape(MultiPolygonFlyZoneShape.POLYGON).maximumFlightHeight(0)
                            .vertices((0 until 32).map { k ->
                                val a = k * Math.PI * 2 / 32
                                LocationCoordinate2D(31.0 + index * .0002 + sin(a) * .0001, 121.0 + cos(a) * .0001)
                            }).build()
                    }).build())
                sample(label, 0, "begin")
                var firstPolygon: Polygon? = null
                repeat(10) { batch ->
                    repeat(10) { step ->
                        if (!deduplicate || (batch == 0 && step == 0)) {
                            inst.runOnMainSync {
                                if (mutateGeometry) {
                                    zones.first().multiPolygonFlyZoneInformation.first().polygonPoints.forEach { p ->
                                        p.latitude += 0.0000001
                                    }
                                }
                                val previous = if (closeChildren) currentPolygons() else emptySet()
                                helper.onFlyZoneListUpdate(zones)
                                if (closeChildren) closeOwnedObjects(previous)
                                if (firstPolygon == null) firstPolygon = currentPolygons().firstOrNull()
                            }
                        }
                        SystemClock.sleep(20)
                    }
                    sample(label, (batch + 1) * 10, "after_refresh")
                    if (verifyFixed) {
                        assertEquals("One live object per polygon, regardless of duplicate IDs/category", 10, polygons)
                        if (!mutateGeometry) inst.runOnMainSync {
                            assertTrue("Unchanged geometry must reuse overlays", currentPolygons().contains(firstPolygon))
                        }
                        else inst.runOnMainSync {
                            assertFalse("In-place SDK coordinate changes must redraw geometry", currentPolygons().contains(firstPolygon))
                        }
                    }
                }
                inst.runOnMainSync {
                    val previous = if (closeChildren) currentPolygons() else emptySet()
                    helper.onFlyZoneListUpdate(emptyList())
                    if (closeChildren) closeOwnedObjects(previous)
                }
                sample(label, 100, "after_helper_clear")
                if (verifyFixed) assertEquals("All category resources must be removed", 0, polygons)
                // Log all categories before reporting the regression, so the control remains comparable.
            }
        } finally {
            inst.runOnMainSync { helper.onFlyZoneListUpdate(emptyList()); map.clear() }
            sample("all", 100, "after_map_clear")
            SystemClock.sleep(3000)
            Runtime.getRuntime().runFinalization()
            sample("all", 100, "after_idle_and_finalization")
            inst.runOnMainSync { activity.finish() }
        }
    }

    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
