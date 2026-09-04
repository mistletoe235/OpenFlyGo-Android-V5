package edu.playground.djivln.survey

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Boundary between DJI/WGS-84 telemetry and mainland-China GCJ-02 map coordinates.
 *
 * All persisted missions stay in WGS-84. Conversion is only applied while
 * rendering map coordinates or accepting taps from the Baidu map view configured for GCJ-02.
 */
object ChinaCoordinateTransform {
    private const val SEMI_MAJOR_AXIS = 6378245.0
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323
    private val mainlandChinaOutline = arrayOf(
        53.56 to 122.34, 52.50 to 120.00, 49.50 to 116.70, 47.00 to 116.50,
        45.00 to 114.00, 43.60 to 112.00, 41.50 to 110.50, 42.50 to 107.50,
        41.60 to 104.50, 42.50 to 101.50, 42.80 to 96.50, 45.20 to 95.00,
        46.50 to 90.00, 48.00 to 89.00, 49.10 to 87.80, 48.20 to 82.00,
        45.00 to 82.30, 42.50 to 80.20, 40.00 to 74.00, 37.00 to 74.50,
        35.50 to 78.00, 33.00 to 79.00, 31.00 to 80.00, 29.00 to 82.00,
        27.80 to 88.10, 28.20 to 92.50, 28.00 to 97.30, 25.60 to 98.20,
        24.00 to 97.60, 21.10 to 101.10, 22.40 to 103.40, 21.50 to 107.00,
        20.90 to 108.10, 21.50 to 108.80, 21.50 to 110.00, 22.00 to 113.50,
        23.50 to 117.50, 25.50 to 120.50, 28.30 to 121.80, 31.80 to 122.20,
        34.50 to 120.50, 37.50 to 122.70, 40.00 to 122.00, 42.50 to 124.50,
        43.00 to 129.00, 44.50 to 131.50, 47.50 to 134.80, 49.50 to 130.50,
        52.00 to 126.50,
    )
    private val hainanOutline = arrayOf(
        20.18 to 110.72, 19.20 to 111.05, 18.15 to 110.58,
        18.05 to 108.62, 19.15 to 108.35, 20.15 to 109.25,
    )

    fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        if (outsideMainlandChina(point.latitude, point.longitude)) return point
        val delta = delta(point.latitude, point.longitude)
        return point.copy(
            latitude = point.latitude + delta.first,
            longitude = point.longitude + delta.second,
        )
    }

    fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        if (outsideMainlandChina(point.latitude, point.longitude)) return point
        // Fixed-point refinement is more accurate than subtracting one forward delta.
        var estimateLatitude = point.latitude
        var estimateLongitude = point.longitude
        repeat(6) {
            val projected = wgs84ToGcj02(GeoPoint(estimateLatitude, estimateLongitude, point.altitudeMeters))
            estimateLatitude -= projected.latitude - point.latitude
            estimateLongitude -= projected.longitude - point.longitude
        }
        return GeoPoint(estimateLatitude, estimateLongitude, point.altitudeMeters)
    }

    fun outsideMainlandChina(latitude: Double, longitude: Double): Boolean =
        !latitude.isFinite() || !longitude.isFinite() ||
            (!insideOutline(latitude, longitude, mainlandChinaOutline) &&
                !insideOutline(latitude, longitude, hainanOutline))

    private fun insideOutline(
        latitude: Double,
        longitude: Double,
        outline: Array<Pair<Double, Double>>,
    ): Boolean {
        var inside = false
        var previous = outline.lastIndex
        outline.indices.forEach { index ->
            val (latitudeA, longitudeA) = outline[index]
            val (latitudeB, longitudeB) = outline[previous]
            if ((latitudeA > latitude) != (latitudeB > latitude) &&
                longitude < (longitudeB - longitudeA) * (latitude - latitudeA) /
                (latitudeB - latitudeA) + longitudeA
            ) inside = !inside
            previous = index
        }
        return inside
    }

    private fun delta(latitude: Double, longitude: Double): Pair<Double, Double> {
        var latitudeOffset = transformLatitude(longitude - 105.0, latitude - 35.0)
        var longitudeOffset = transformLongitude(longitude - 105.0, latitude - 35.0)
        val latitudeRadians = Math.toRadians(latitude)
        var magic = sin(latitudeRadians)
        magic = 1.0 - ECCENTRICITY_SQUARED * magic * magic
        val sqrtMagic = sqrt(magic)
        latitudeOffset = latitudeOffset * 180.0 /
            ((SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic) * Math.PI)
        longitudeOffset = longitudeOffset * 180.0 /
            (SEMI_MAJOR_AXIS / sqrtMagic * cos(latitudeRadians) * Math.PI)
        return latitudeOffset to longitudeOffset
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return result
    }
}
