package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.ImageWindow
import mil.nga.tiff.TiffReader
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class TerrainRasterInfo(
    val displayName: String,
    val width: Int,
    val height: Int,
    val epsg: Int,
    val noDataValue: Double?,
    val pixelSizeX: Double,
    val pixelSizeY: Double,
    val minimumLatitude: Double,
    val maximumLatitude: Double,
    val minimumLongitude: Double,
    val maximumLongitude: Double,
)

data class TerrainPreviewData(
    val info: TerrainRasterInfo,
    val elevations: DoubleArray,
    val columns: Int,
    val rows: Int,
)

/** Read-only elevation surface addressed with WGS84 latitude/longitude. */
interface TerrainElevationSource {
    val info: TerrainRasterInfo
    fun elevationMeters(latitude: Double, longitude: Double): Double
}

/**
 * Android-safe GeoTIFF DSM/DEM reader. Supported CRSs are WGS84 geographic,
 * Web Mercator and WGS84 UTM north/south. Rotated rasters are rejected rather
 * than silently producing an unsafe flight profile.
 */
class GeoTiffTerrain private constructor(
    private val directory: FileDirectory,
    override val info: TerrainRasterInfo,
    private val transform: RasterTransform,
    private val projection: Projection,
    private val context: Context?,
) : TerrainElevationSource {
    fun previewGrid(columns: Int = 72, rows: Int = 48): DoubleArray {
        require(columns in 2..256 && rows in 2..256)
        val result = DoubleArray(columns * rows) { Double.NaN }
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = column.toDouble() / (columns - 1) * (info.width - 1)
            val y = row.toDouble() / (rows - 1) * (info.height - 1)
            val model = transform.pixelToModel(x, y)
            val coordinate = projection.toWgs84(model.first, model.second)
            result[row * columns + column] = runCatching {
                elevationMeters(coordinate.first, coordinate.second)
            }.getOrDefault(Double.NaN)
        }
        return result
    }

    fun previewForArea(
        roi: List<GeoPoint>,
        columns: Int = 72,
        rows: Int = 48,
    ): TerrainPreviewData {
        require(roi.size >= 3)
        TerrainImportSafety.requireCompleteCoverage(this, roi, context)
        val rawMinLat = roi.minOf { it.latitude }
        val rawMaxLat = roi.maxOf { it.latitude }
        val rawMinLon = roi.minOf { it.longitude }
        val rawMaxLon = roi.maxOf { it.longitude }
        val latMargin = maxOf((rawMaxLat - rawMinLat) * 0.12, 1e-6)
        val lonMargin = maxOf((rawMaxLon - rawMinLon) * 0.12, 1e-6)
        val minLat = (rawMinLat - latMargin).coerceAtLeast(info.minimumLatitude)
        val maxLat = (rawMaxLat + latMargin).coerceAtMost(info.maximumLatitude)
        val minLon = (rawMinLon - lonMargin).coerceAtLeast(info.minimumLongitude)
        val maxLon = (rawMaxLon + lonMargin).coerceAtMost(info.maximumLongitude)
        require(minLat < maxLat && minLon < maxLon) {
            terrainText(context, R.string.roi_outside_dsm_coverage, "Survey area is outside DSM coverage")
        }
        val values = DoubleArray(columns * rows) { Double.NaN }
        for (row in 0 until rows) for (column in 0 until columns) {
            val latitude = maxLat - row.toDouble() / (rows - 1) * (maxLat - minLat)
            val longitude = minLon + column.toDouble() / (columns - 1) * (maxLon - minLon)
            values[row * columns + column] = runCatching {
                elevationMeters(latitude, longitude)
            }.getOrDefault(Double.NaN)
        }
        return TerrainPreviewData(
            info.copy(
                minimumLatitude = minLat,
                maximumLatitude = maxLat,
                minimumLongitude = minLon,
                maximumLongitude = maxLon,
            ),
            values,
            columns,
            rows,
        )
    }

    override fun elevationMeters(latitude: Double, longitude: Double): Double {
        require(latitude.isFinite() && longitude.isFinite()) {
            terrainText(context, R.string.coordinate_invalid, "Invalid coordinate")
        }
        val model = projection.fromWgs84(latitude, longitude)
        val pixel = transform.modelToPixel(model.first, model.second)
        // GeoTIFF pixels describe cell centres for sampling purposes.
        val x = pixel.first.coerceIn(0.0, info.width - 1.0)
        val y = pixel.second.coerceIn(0.0, info.height - 1.0)
        require(pixel.first >= -0.5 && pixel.first <= info.width - 0.5 &&
            pixel.second >= -0.5 && pixel.second <= info.height - 0.5) {
            terrainText(context, R.string.coordinate_outside_dsm_coverage, "Coordinate is outside DSM coverage")
        }
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = minOf(x0 + 1, info.width - 1)
        val y1 = minOf(y0 + 1, info.height - 1)
        val raster = directory.readRasters(ImageWindow(x0, y0, x1 + 1, y1 + 1), intArrayOf(0))
        fun value(px: Int, py: Int): Double {
            val sample = raster.getFirstPixelSample(px - x0, py - y0).toDouble()
            require(sample.isFinite() && !isNoData(sample, info.noDataValue)) {
                terrainText(context, R.string.dsm_nodata_at_target, "DSM contains NoData at the target location")
            }
            return sample
        }
        val q00 = value(x0, y0)
        val q10 = value(x1, y0)
        val q01 = value(x0, y1)
        val q11 = value(x1, y1)
        val tx = x - x0
        val ty = y - y0
        return (q00 * (1.0 - tx) + q10 * tx) * (1.0 - ty) +
            (q01 * (1.0 - tx) + q11 * tx) * ty
    }

    companion object {
        fun read(input: InputStream, displayName: String, context: Context? = null): GeoTiffTerrain {
            val image = TiffReader.readTiff(input, false)
            require(image.fileDirectories.isNotEmpty()) {
                terrainText(context, R.string.geotiff_primary_image_missing, "GeoTIFF has no primary image")
            }
            val directory = image.fileDirectory
            directory.setCache(true)
            val width = directory.imageWidth.toInt()
            val height = directory.imageHeight.toInt()
            require(width > 1 && height > 1) {
                terrainText(context, R.string.dsm_raster_size_invalid, "Invalid DSM raster size")
            }
            require(directory.samplesPerPixel >= 1) {
                terrainText(context, R.string.dsm_elevation_band_missing, "DSM has no elevation band")
            }
            val keys = parseGeoKeys(directory, context)
            val epsg = keys[3072] ?: keys[2048]
                ?: error(terrainText(context, R.string.geotiff_epsg_missing, "GeoTIFF is missing an EPSG coordinate system"))
            val projection = Projection.forEpsg(epsg, context)
            val pixelIsArea = (keys[1025] ?: 1) == 1
            val transform = RasterTransform.from(directory, pixelIsArea = pixelIsArea, context = context)
            val firstPixel = if (pixelIsArea) -0.5 else 0.0
            val lastPixelX = if (pixelIsArea) width - 0.5 else width - 1.0
            val lastPixelY = if (pixelIsArea) height - 0.5 else height - 1.0
            val corners = listOf(
                transform.pixelToModel(firstPixel, firstPixel),
                transform.pixelToModel(lastPixelX, firstPixel),
                transform.pixelToModel(firstPixel, lastPixelY),
                transform.pixelToModel(lastPixelX, lastPixelY),
            ).map { projection.toWgs84(it.first, it.second) }
            val noData = directory.getStringEntryValue(FieldTagType.GDAL_NODATA)
                ?.trim('\u0000', ' ', '|')?.toDoubleOrNull()
            return GeoTiffTerrain(
                directory = directory,
                info = TerrainRasterInfo(
                    displayName = displayName,
                    width = width,
                    height = height,
                    epsg = epsg,
                    noDataValue = noData,
                    pixelSizeX = transform.pixelSizeX,
                    pixelSizeY = transform.pixelSizeY,
                    minimumLatitude = corners.minOf { it.first },
                    maximumLatitude = corners.maxOf { it.first },
                    minimumLongitude = corners.minOf { it.second },
                    maximumLongitude = corners.maxOf { it.second },
                ),
                transform = transform,
                projection = projection,
                context = context,
            )
        }

        private fun parseGeoKeys(directory: FileDirectory, context: Context?): Map<Int, Int> {
            val raw = directory.get(FieldTagType.GeoKeyDirectory)?.values as? List<*>
                ?: error(terrainText(context, R.string.geotiff_geokey_directory_missing, "GeoTIFF is missing GeoKeyDirectory"))
            val values = raw.map { (it as Number).toInt() }
            require(values.size >= 4 && values[3] >= 1) {
                terrainText(context, R.string.geokey_directory_invalid, "Invalid GeoKeyDirectory")
            }
            val result = mutableMapOf<Int, Int>()
            repeat(values[3]) { index ->
                val offset = 4 + index * 4
                require(offset + 3 < values.size) {
                    terrainText(context, R.string.geokey_directory_truncated, "GeoKeyDirectory is truncated")
                }
                val key = values[offset]
                val location = values[offset + 1]
                val count = values[offset + 2]
                val value = values[offset + 3]
                if (location == 0 && count == 1) result[key] = value
            }
            return result
        }

        private fun isNoData(value: Double, noData: Double?): Boolean = when {
            noData == null -> false
            noData.isNaN() -> value.isNaN()
            else -> kotlin.math.abs(value - noData) <= maxOf(1.0, kotlin.math.abs(noData)) * 1e-9
        }
    }
}

private data class RasterTransform(
    val originPixelX: Double,
    val originPixelY: Double,
    val originModelX: Double,
    val originModelY: Double,
    val pixelSizeX: Double,
    val pixelSizeY: Double,
    val pixelCenterOffset: Double,
) {
    fun pixelToModel(x: Double, y: Double) = Pair(
        originModelX + (x + pixelCenterOffset - originPixelX) * pixelSizeX,
        originModelY - (y + pixelCenterOffset - originPixelY) * pixelSizeY,
    )
    fun modelToPixel(x: Double, y: Double) = Pair(
        originPixelX + (x - originModelX) / pixelSizeX - pixelCenterOffset,
        originPixelY + (originModelY - y) / pixelSizeY - pixelCenterOffset,
    )

    companion object {
        fun from(directory: FileDirectory, pixelIsArea: Boolean, context: Context? = null): RasterTransform {
            val centerOffset = if (pixelIsArea) 0.5 else 0.0
            val matrixEntry = directory.get(FieldTagType.ModelTransformation)
            if (matrixEntry != null) {
                val matrix = (matrixEntry.values as? List<*>)
                    ?.map { (it as Number).toDouble() }
                    ?: error(terrainText(context, R.string.geotiff_model_transform_invalid, "Invalid GeoTIFF ModelTransformation"))
                require(matrix.size == 16) {
                    terrainText(context, R.string.geotiff_model_transform_length_invalid, "Invalid GeoTIFF ModelTransformation length")
                }
                val epsilon = 1e-12
                fun isZero(index: Int) = kotlin.math.abs(matrix[index]) <= epsilon
                require(
                    matrix[0] > 0.0 && matrix[5] < 0.0 &&
                        isZero(1) && isZero(2) && isZero(4) && isZero(6) &&
                        isZero(8) && isZero(9) && isZero(11) &&
                        isZero(12) && isZero(13) && isZero(14) &&
                        kotlin.math.abs(matrix[15] - 1.0) <= epsilon
                ) {
                    terrainText(
                        context,
                        R.string.geotiff_rotation_shear_unsupported,
                        "Rotated/sheared GeoTIFF is not supported; export a north-up DSM",
                    )
                }
                return RasterTransform(
                    originPixelX = 0.0,
                    originPixelY = 0.0,
                    originModelX = matrix[3],
                    originModelY = matrix[7],
                    pixelSizeX = matrix[0],
                    pixelSizeY = -matrix[5],
                    pixelCenterOffset = centerOffset,
                )
            }
            val scale = directory.modelPixelScale
            val tie = directory.modelTiepoint
            require(scale != null && scale.size >= 2 && tie != null && tie.size >= 6) {
                terrainText(context, R.string.geotiff_scale_tiepoint_missing, "GeoTIFF is missing pixel scale or tiepoints")
            }
            require(scale[0] > 0.0 && scale[1] > 0.0) {
                terrainText(context, R.string.geotiff_pixel_size_invalid, "Invalid GeoTIFF pixel size")
            }
            return RasterTransform(tie[0], tie[1], tie[3], tie[4], scale[0], scale[1], centerOffset)
        }
    }
}

private interface Projection {
    fun fromWgs84(latitude: Double, longitude: Double): Pair<Double, Double>
    fun toWgs84(x: Double, y: Double): Pair<Double, Double>

    companion object {
        fun forEpsg(epsg: Int, context: Context? = null): Projection = when (epsg) {
            4326 -> GeographicProjection
            3857 -> WebMercatorProjection
            in 32601..32660 -> UtmProjection(epsg - 32600, false)
            in 32701..32760 -> UtmProjection(epsg - 32700, true)
            else -> error(
                terrainText(
                    context,
                    R.string.geotiff_epsg_unsupported,
                    "EPSG:$epsg is not supported; supported: 4326, 3857, WGS84 UTM 326xx/327xx",
                    epsg,
                ),
            )
        }
    }
}

private fun terrainText(context: Context?, resourceId: Int, fallback: String, vararg arguments: Any): String =
    context?.getString(resourceId, *arguments) ?: fallback

private object GeographicProjection : Projection {
    override fun fromWgs84(latitude: Double, longitude: Double) = Pair(longitude, latitude)
    override fun toWgs84(x: Double, y: Double) = Pair(y, x)
}

private object WebMercatorProjection : Projection {
    private const val R = 6378137.0
    override fun fromWgs84(latitude: Double, longitude: Double) = Pair(
        Math.toRadians(longitude) * R,
        ln(tan(PI / 4.0 + Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)) / 2.0)) * R,
    )
    override fun toWgs84(x: Double, y: Double) = Pair(
        Math.toDegrees(2.0 * atan(exp(y / R)) - PI / 2.0),
        Math.toDegrees(x / R),
    )
}

private class UtmProjection(private val zone: Int, private val south: Boolean) : Projection {
    private val a = 6378137.0
    private val e = 0.08181919084262149
    private val e2 = e * e
    private val ep2 = e2 / (1.0 - e2)
    private val k0 = 0.9996
    private val lon0 = Math.toRadians(zone * 6.0 - 183.0)

    override fun fromWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val n = a / sqrt(1.0 - e2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = ep2 * cos(lat).pow(2)
        val aa = cos(lat) * (lon - lon0)
        val m = meridionalArc(lat)
        val x = 500000.0 + k0 * n * (aa + (1 - t + c) * aa.pow(3) / 6.0 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa.pow(5) / 120.0)
        var y = k0 * (m + n * tan(lat) * (aa * aa / 2.0 +
            (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24.0 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa.pow(6) / 720.0))
        if (south) y += 10_000_000.0
        return Pair(x, y)
    }

    override fun toWgs84(x: Double, y: Double): Pair<Double, Double> {
        val xx = x - 500000.0
        val yy = if (south) y - 10_000_000.0 else y
        val m = yy / k0
        val mu = m / (a * (1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val fp = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            151 * e1.pow(3) / 96 * sin(6 * mu) + 1097 * e1.pow(4) / 512 * sin(8 * mu)
        val c1 = ep2 * cos(fp).pow(2)
        val t1 = tan(fp).pow(2)
        val n1 = a / sqrt(1 - e2 * sin(fp).pow(2))
        val r1 = a * (1 - e2) / (1 - e2 * sin(fp).pow(2)).pow(1.5)
        val d = xx / (n1 * k0)
        val lat = fp - n1 * tan(fp) / r1 * (d * d / 2 -
            (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lon = lon0 + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120) /
            cos(fp)
        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    private fun meridionalArc(lat: Double): Double = a * (
        (1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256) * lat -
            (3 * e2 / 8 + 3 * e2.pow(2) / 32 + 45 * e2.pow(3) / 1024) * sin(2 * lat) +
            (15 * e2.pow(2) / 256 + 45 * e2.pow(3) / 1024) * sin(4 * lat) -
            35 * e2.pow(3) / 3072 * sin(6 * lat))
}
