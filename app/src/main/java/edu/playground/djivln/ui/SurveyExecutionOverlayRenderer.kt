package edu.playground.djivln.ui

import edu.playground.djivln.survey.GeoPoint

internal class SurveyExecutionOverlayRenderer(private val surface: Surface) {
    data class LineStyle(val color: Int, val width: Float, val zIndex: Float, val transit: Boolean = false)

    interface Line {
        fun setPoints(points: List<GeoPoint>)
        fun setColor(color: Int)
        fun remove()
    }

    interface Marker {
        fun setPoint(point: GeoPoint)
        fun setTitle(title: String)
        fun setColor(color: Int)
        fun remove()
    }

    interface Surface {
        fun addLine(points: List<GeoPoint>, style: LineStyle): Line
        fun addMarker(point: GeoPoint, title: String, color: Int, zIndex: Int): Marker
    }

    private var previous: SurveyExecutionOverlay? = null
    private var route: Line? = null
    private var halo: Line? = null
    private var activeRoute: Line? = null
    private var target: Marker? = null
    private var recovery: Marker? = null

    fun render(overlay: SurveyExecutionOverlay?) {
        if (overlay == null) {
            clear()
            return
        }
        val old = previous
        if (old == overlay) return
        val activeColor = if (overlay.paused) 0xFFFFA726.toInt() else 0xFF00B8D4.toInt()
        if (overlay.activeRoutePoints.size < 2) {
            halo?.remove()
            activeRoute?.remove()
            halo = null
            activeRoute = null
        } else if (activeRoute == null) {
            halo = surface.addLine(overlay.activeRoutePoints, LineStyle(0xFFFFFFFF.toInt(), 15f, 38f))
            activeRoute = surface.addLine(overlay.activeRoutePoints, LineStyle(activeColor, 10f, 39f))
        } else {
            if (old?.activeRoutePoints != overlay.activeRoutePoints) {
                halo?.setPoints(overlay.activeRoutePoints)
                activeRoute?.setPoints(overlay.activeRoutePoints)
            }
            if (old?.paused != overlay.paused) activeRoute?.setColor(activeColor)
        }

        if (overlay.aircraftPoint == null) {
            route?.remove()
            route = null
        } else {
            if (old?.transit != overlay.transit) {
                route?.remove()
                route = null
            }
            if (route == null) {
                route = surface.addLine(
                    listOf(overlay.aircraftPoint, overlay.targetPoint),
                    LineStyle(
                        if (overlay.transit) 0xFF87919C.toInt() else 0xFF00E5FF.toInt(),
                        if (overlay.transit) 5f else 8f,
                        40f,
                        overlay.transit,
                    ),
                )
            } else if (old?.aircraftPoint != overlay.aircraftPoint || old.targetPoint != overlay.targetPoint) {
                route?.setPoints(listOf(overlay.aircraftPoint, overlay.targetPoint))
            }
        }

        if (target == null) {
            target = surface.addMarker(overlay.targetPoint, overlay.title, targetColor(overlay), 42)
        } else {
            if (old?.targetPoint != overlay.targetPoint) target?.setPoint(overlay.targetPoint)
            if (old?.title != overlay.title) target?.setTitle(overlay.title)
            if (old == null || targetColor(old) != targetColor(overlay)) target?.setColor(targetColor(overlay))
        }

        if (overlay.recoveryPoint == null) {
            recovery?.remove()
            recovery = null
        } else if (recovery == null) {
            recovery = surface.addMarker(overlay.recoveryPoint, overlay.recoveryTitle.orEmpty(), 0xFFFF9800.toInt(), 43)
        } else {
            if (old?.recoveryPoint != overlay.recoveryPoint) recovery?.setPoint(overlay.recoveryPoint)
            if (old?.recoveryTitle != overlay.recoveryTitle) recovery?.setTitle(overlay.recoveryTitle.orEmpty())
        }
        previous = overlay
    }

    fun clear() {
        route?.remove()
        halo?.remove()
        activeRoute?.remove()
        target?.remove()
        recovery?.remove()
        route = null
        halo = null
        activeRoute = null
        target = null
        recovery = null
        previous = null
    }

    private fun targetColor(overlay: SurveyExecutionOverlay): Int = when {
        overlay.paused -> 0xFFF29A2E.toInt()
        overlay.transit -> 0xFF87919C.toInt()
        else -> 0xFF00A8C6.toInt()
    }
}
