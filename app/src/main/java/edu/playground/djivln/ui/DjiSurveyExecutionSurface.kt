package edu.playground.djivln.ui

import dji.v5.ux.mapkit.core.maps.DJIMap
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor
import dji.v5.ux.mapkit.core.models.DJILatLng
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions
import dji.v5.ux.mapkit.core.models.annotations.DJIPolylineOptions
import edu.playground.djivln.survey.GeoPoint

internal class DjiSurveyExecutionSurface(
    private val map: DJIMap,
    private val icon: (Int) -> DJIBitmapDescriptor,
) : SurveyExecutionOverlayRenderer.Surface {
    override fun addLine(
        points: List<GeoPoint>,
        style: SurveyExecutionOverlayRenderer.LineStyle,
    ): SurveyExecutionOverlayRenderer.Line {
        val line = map.addPolyline(
            DJIPolylineOptions().addAll(points.map(::coordinate))
                .color(style.color).width(style.width).zIndex(style.zIndex)
                .setDashed(style.transit).setDashLength(if (style.transit) 9f else 3f),
        )
        return object : SurveyExecutionOverlayRenderer.Line {
            override fun setPoints(points: List<GeoPoint>) = line.setPoints(points.map(::coordinate))
            override fun setColor(color: Int) = line.setColor(color)
            override fun remove() = line.remove()
        }
    }

    override fun addMarker(point: GeoPoint, title: String, color: Int, zIndex: Int): SurveyExecutionOverlayRenderer.Marker {
        val marker = map.addMarker(
            DJIMarkerOptions().position(coordinate(point)).title(title)
                .icon(icon(color)).anchor(0.5f, 0.5f).zIndex(zIndex),
        )
        return object : SurveyExecutionOverlayRenderer.Marker {
            override fun setPoint(point: GeoPoint) = marker.setPosition(coordinate(point))
            override fun setTitle(title: String) = marker.setTitle(title)
            override fun setColor(color: Int) = marker.setIcon(icon(color))
            override fun remove() = marker.remove()
        }
    }

    private fun coordinate(point: GeoPoint) = DJILatLng(point.latitude, point.longitude)
}
