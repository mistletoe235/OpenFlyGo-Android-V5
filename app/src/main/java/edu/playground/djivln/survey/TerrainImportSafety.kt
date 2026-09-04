package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class TerrainCoverageReport(
    val boundsCoverRoi: Boolean,
    val sampledPointCount: Int,
    val missingSampleCount: Int,
) {
    val complete: Boolean
        get() = boundsCoverRoi && sampledPointCount > 0 && missingSampleCount == 0
}

/** Mobile-memory and geographic-coverage checks shared by DSM/DEM import paths. */
object TerrainImportSafety {
    const val MAX_IMPORT_BYTES: Long = 96L * 1024L * 1024L
    private const val COVERAGE_GRID_COLUMNS = 16
    private const val COVERAGE_GRID_ROWS = 16
    private const val EDGE_SAMPLES_PER_SEGMENT = 8
    private const val BOUNDS_EPSILON_DEGREES = 1e-9

    fun readBounded(
        input: InputStream,
        maximumBytes: Long = MAX_IMPORT_BYTES,
        context: Context? = null,
    ): ByteArray {
        require(maximumBytes > 0L) { "maximumBytes must be positive" }
        val output = ByteArrayOutputStream(minOf(maximumBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maximumBytes) {
                text(
                    context,
                    R.string.terrain_file_mobile_memory_limit,
                    "Elevation file exceeds the ${maximumBytes / (1024L * 1024L)} MB mobile memory safety limit; crop it to the survey area before importing",
                    maximumBytes / (1024L * 1024L),
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun inspectCoverage(
        source: TerrainElevationSource,
        roi: List<GeoPoint>,
        context: Context? = null,
    ): TerrainCoverageReport {
        require(roi.size >= 3) {
            text(context, R.string.terrain_roi_requires_three_points, "Survey area requires at least 3 boundary points")
        }
        val info = source.info
        fun inBounds(point: GeoPoint): Boolean =
            point.latitude >= info.minimumLatitude - BOUNDS_EPSILON_DEGREES &&
                point.latitude <= info.maximumLatitude + BOUNDS_EPSILON_DEGREES &&
                point.longitude >= info.minimumLongitude - BOUNDS_EPSILON_DEGREES &&
                point.longitude <= info.maximumLongitude + BOUNDS_EPSILON_DEGREES

        val boundsCover = roi.all(::inBounds)
        if (!boundsCover) return TerrainCoverageReport(false, 0, 0)

        val samples = LinkedHashSet<Pair<Double, Double>>()
        roi.forEach { samples += it.latitude to it.longitude }
        roi.indices.forEach { index ->
            val from = roi[index]
            val to = roi[(index + 1) % roi.size]
            for (step in 1 until EDGE_SAMPLES_PER_SEGMENT) {
                val ratio = step.toDouble() / EDGE_SAMPLES_PER_SEGMENT
                samples += (from.latitude + (to.latitude - from.latitude) * ratio) to
                    (from.longitude + (to.longitude - from.longitude) * ratio)
            }
        }
        val minLat = roi.minOf { it.latitude }
        val maxLat = roi.maxOf { it.latitude }
        val minLon = roi.minOf { it.longitude }
        val maxLon = roi.maxOf { it.longitude }
        for (row in 0 until COVERAGE_GRID_ROWS) {
            val latitude = minLat + (maxLat - minLat) * (row + 0.5) / COVERAGE_GRID_ROWS
            for (column in 0 until COVERAGE_GRID_COLUMNS) {
                val longitude = minLon + (maxLon - minLon) * (column + 0.5) / COVERAGE_GRID_COLUMNS
                if (insidePolygon(latitude, longitude, roi)) samples += latitude to longitude
            }
        }
        var missing = 0
        samples.forEach { (latitude, longitude) ->
            val valid = runCatching { source.elevationMeters(latitude, longitude) }
                .getOrNull()?.isFinite() == true
            if (!valid) missing += 1
        }
        return TerrainCoverageReport(true, samples.size, missing)
    }

    fun requireCompleteCoverage(
        source: TerrainElevationSource,
        roi: List<GeoPoint>,
        context: Context? = null,
    ): TerrainCoverageReport {
        val report = inspectCoverage(source, roi, context)
        require(report.boundsCoverRoi) {
            text(
                context,
                R.string.terrain_roi_not_fully_covered,
                "Elevation data does not fully cover the survey area; import a DSM/DEM covering its entire boundary",
            )
        }
        require(report.sampledPointCount > 0 && report.missingSampleCount == 0) {
            text(
                context,
                R.string.terrain_roi_nodata_gaps,
                "Survey elevation contains NoData or unreadable gaps (sampled ${report.missingSampleCount}/${report.sampledPointCount}); replace or repair the data",
                report.missingSampleCount,
                report.sampledPointCount,
            )
        }
        return report
    }

    private fun text(context: Context?, resourceId: Int, fallback: String, vararg args: Any): String =
        context?.getString(resourceId, *args) ?: fallback

    private fun insidePolygon(latitude: Double, longitude: Double, polygon: List<GeoPoint>): Boolean {
        var inside = false
        var previous = polygon.lastIndex
        polygon.indices.forEach { index ->
            val a = polygon[index]
            val b = polygon[previous]
            if ((a.latitude > latitude) != (b.latitude > latitude) &&
                longitude < (b.longitude - a.longitude) * (latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude
            ) inside = !inside
            previous = index
        }
        return inside
    }
}
