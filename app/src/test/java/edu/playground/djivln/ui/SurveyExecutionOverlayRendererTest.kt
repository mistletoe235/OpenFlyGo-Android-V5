package edu.playground.djivln.ui

import edu.playground.djivln.survey.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyExecutionOverlayRendererTest {
    private val origin = GeoPoint(31.0, 121.0)
    private val destination = GeoPoint(31.001, 121.001)
    private val overlay = SurveyExecutionOverlay(
        aircraftPoint = origin,
        targetPoint = destination,
        activeRoutePoints = listOf(origin, destination),
        recoveryPoint = origin,
        recoveryTitle = "Recovery",
        title = "Target",
    )

    @Test
    fun thirtyMinutesOf100HzTelemetryKeepsOnePendingRenderAndReusesAllLayers() {
        val surface = FakeSurface()
        val renderer = SurveyExecutionOverlayRenderer(surface)
        val scheduler = FakeScheduler()
        var frames = 0
        val updates = scheduler.updates<SurveyExecutionOverlay?> {
            frames++
            renderer.render(it)
        }
        var latest = overlay
        repeat(180000) { sequence ->
            latest = overlay.copy(aircraftPoint = GeoPoint(31.0 + sequence * 0.00000001, 121.0))
            updates.submit(latest)
            scheduler.advance(10)
        }
        scheduler.advance(200)
        assertEquals(1, scheduler.maxPending)
        assertTrue("Display updates: $frames", frames in 9000..9001)
        assertEquals(3, surface.lines.size)
        assertEquals(2, surface.markers.size)
        assertEquals(0, surface.lines[0].updates)
        assertEquals(0, surface.lines[1].updates)
        assertTrue(surface.lines[2].updates <= 9000)
        assertEquals(latest.aircraftPoint, surface.lines[2].points.first())
        assertTrue(surface.markers.all { it.updates == 0 && it.removes == 0 })
        assertTrue(surface.lines.all { it.removes == 0 })
    }

    @Test
    fun duplicateSnapshotsDoNotSubmitAnyMapUpdates() {
        val surface = FakeSurface()
        val renderer = SurveyExecutionOverlayRenderer(surface)
        repeat(10000) { renderer.render(overlay.copy(activeRoutePoints = overlay.activeRoutePoints.toList())) }
        assertEquals(3, surface.lines.size)
        assertEquals(2, surface.markers.size)
        assertTrue(surface.lines.all { it.updates == 0 })
        assertTrue(surface.markers.all { it.updates == 0 })
    }

    @Test
    fun targetAndActivePassMoveWithoutRecreatingMarkersOrLines() {
        val surface = FakeSurface()
        val renderer = SurveyExecutionOverlayRenderer(surface)
        renderer.render(overlay)
        val next = overlay.copy(
            targetPoint = GeoPoint(31.002, 121.002), title = "Next",
            activeRoutePoints = listOf(destination, GeoPoint(31.002, 121.002)),
        )
        renderer.render(next)
        assertEquals(3, surface.lines.size)
        assertEquals(2, surface.markers.size)
        assertEquals(next.activeRoutePoints, surface.lines[0].points)
        assertEquals(next.activeRoutePoints, surface.lines[1].points)
        assertEquals(next.targetPoint, surface.lines[2].points.last())
        assertEquals(next.targetPoint, surface.markers[0].point)
        assertEquals("Next", surface.markers[0].title)
        assertTrue(surface.lines.all { it.removes == 0 })
    }

    @Test
    fun pauseUpdatesColorsAndTransitOnlyRecreatesTheStyledConnector() {
        val surface = FakeSurface()
        val renderer = SurveyExecutionOverlayRenderer(surface)
        renderer.render(overlay)
        renderer.render(overlay.copy(paused = true))
        assertEquals(0xFFFFA726.toInt(), surface.lines[1].color)
        assertEquals(0xFFF29A2E.toInt(), surface.markers[0].color)
        assertEquals(3, surface.lines.size)
        renderer.render(overlay.copy(transit = true))
        assertEquals(4, surface.lines.size)
        assertEquals(1, surface.lines[2].removes)
        assertEquals(0, surface.lines[0].removes)
        assertEquals(0, surface.lines[1].removes)
        assertTrue(surface.lines[3].style.transit)
        assertEquals(0xFF87919C.toInt(), surface.markers[0].color)
        assertEquals(2, surface.markers.size)
    }

    @Test
    fun missingGeometryRemovesOnlyItsLayersAndCanRecover() {
        val surface = FakeSurface()
        val renderer = SurveyExecutionOverlayRenderer(surface)
        renderer.render(overlay)
        renderer.render(overlay.copy(aircraftPoint = null, recoveryPoint = null, activeRoutePoints = emptyList()))
        assertTrue(surface.lines.all { it.removes == 1 })
        assertEquals(0, surface.markers[0].removes)
        assertEquals(1, surface.markers[1].removes)
        renderer.render(overlay)
        assertEquals(6, surface.lines.size)
        assertEquals(3, surface.markers.size)
        renderer.render(null)
        renderer.render(null)
        assertTrue(surface.lines.all { it.removes == 1 })
        assertTrue(surface.markers.all { it.removes == 1 })
    }

    @Test
    fun stalledUiUsesLatestValueRatherThanAQueueOfOldPositions() {
        val scheduler = FakeScheduler()
        val shown = mutableListOf<Int>()
        val updates = scheduler.updates<Int>(shown::add)
        repeat(100000) { updates.submit(it) }
        assertEquals(1, scheduler.maxPending)
        scheduler.advance(1)
        assertEquals(listOf(99999), shown)
        updates.submit(100000)
        scheduler.advance(199)
        assertEquals(listOf(99999), shown)
        scheduler.advance(1)
        assertEquals(listOf(99999, 100000), shown)
    }

    @Test
    fun finishingOrClosingDropsPendingWorkAndRestartShowsFreshState() {
        val scheduler = FakeScheduler()
        val shown = mutableListOf<Int?>()
        val updates = scheduler.updates<Int?>(shown::add)
        updates.submit(1)
        updates.clear()
        scheduler.advance(500)
        assertTrue(shown.isEmpty())
        updates.submit(2)
        scheduler.advance(1)
        updates.submit(null)
        scheduler.advance(200)
        assertEquals(listOf(2, null), shown)
    }

    private class FakeScheduler {
        var now = 0L
        var maxPending = 0
        val scheduled = mutableMapOf<Runnable, Long>()

        fun <Value> updates(render: (Value) -> Unit) = LatestMapUpdate(
            clockMillis = { now },
            schedule = { task, delay ->
                scheduled[task] = now + delay
                maxPending = maxOf(maxPending, scheduled.size)
            },
            cancel = { scheduled.remove(it); Unit },
            render = render,
        )

        fun advance(millis: Long) {
            now += millis
            scheduled.filterValues { it <= now }.keys.toList().forEach {
                scheduled.remove(it)
                it.run()
            }
        }
    }

    private class FakeSurface : SurveyExecutionOverlayRenderer.Surface {
        val lines = mutableListOf<FakeLine>()
        val markers = mutableListOf<FakeMarker>()

        override fun addLine(points: List<GeoPoint>, style: SurveyExecutionOverlayRenderer.LineStyle) =
            FakeLine(points.toList(), style).also(lines::add)

        override fun addMarker(point: GeoPoint, title: String, color: Int, zIndex: Int) =
            FakeMarker(point, title, color).also(markers::add)
    }

    private class FakeLine(
        @JvmField var points: List<GeoPoint>,
        val style: SurveyExecutionOverlayRenderer.LineStyle,
    ) : SurveyExecutionOverlayRenderer.Line {
        @JvmField var color = style.color
        var updates = 0
        var removes = 0
        override fun setPoints(points: List<GeoPoint>) { this.points = points.toList(); updates++ }
        override fun setColor(color: Int) { this.color = color; updates++ }
        override fun remove() { removes++ }
    }

    private class FakeMarker(
        @JvmField var point: GeoPoint,
        @JvmField var title: String,
        @JvmField var color: Int,
    ) : SurveyExecutionOverlayRenderer.Marker {
        var updates = 0
        var removes = 0
        override fun setPoint(point: GeoPoint) { this.point = point; updates++ }
        override fun setTitle(title: String) { this.title = title; updates++ }
        override fun setColor(color: Int) { this.color = color; updates++ }
        override fun remove() { removes++ }
    }
}
