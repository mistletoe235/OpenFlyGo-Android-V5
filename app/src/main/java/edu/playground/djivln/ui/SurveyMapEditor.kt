package edu.playground.djivln.ui

import edu.playground.djivln.survey.GeoPoint

data class SurveyExecutionOverlay(
    val aircraftPoint: GeoPoint?,
    val targetPoint: GeoPoint,
    val activeRoutePoints: List<GeoPoint> = emptyList(),
    val recoveryPoint: GeoPoint? = null,
    val recoveryTitle: String? = null,
    val title: String,
    val paused: Boolean = false,
    val transit: Boolean = false,
    val activeRegionId: String? = null,
)

/** Map editing boundary shared by the V4-style survey UI and the active map provider. */
interface SurveyMapEditor {
    fun beginEditing(
        onMapTap: (GeoPoint) -> Unit,
        onVertexTap: (Int) -> Unit,
    )

    fun renderVertices(roi: List<GeoPoint>, selectedIndex: Int? = null)
    fun renderReplayPoint(point: GeoPoint?, headingDegrees: Double = 0.0, captureActive: Boolean = false)
    fun renderSimulatorOrigin(point: GeoPoint?) = Unit
    fun renderExecutionOverlay(overlay: SurveyExecutionOverlay?) = Unit
    fun setThreeDimensional(enabled: Boolean): Boolean = false
    fun endEditing()
}
