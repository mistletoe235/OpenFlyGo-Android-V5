package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.floor

data class BuildingHeightDownloadResult(
    val heightAboveGround: TerrainElevationSource,
    val sha256: String,
    val bytes: Long,
    val cached: Boolean,
    val sourceVersion: String?,
    val tileCount: Int,
    val cacheFiles: List<File> = emptyList(),
)

data class BuildingHeightTile(
    val x: Int,
    val y: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

object GlobalBuildingHeightTiles {
    const val TILE_SPAN_DEGREES = 0.2
    const val MAXIMUM_ROI_SPAN_DEGREES = 0.25
    const val MAXIMUM_TILE_COUNT = 9

    fun covering(roi: List<GeoPoint>, context: Context? = null): List<BuildingHeightTile> {
        require(roi.size >= 3) {
            context?.getString(R.string.draw_three_roi_points_first) ?: "Draw at least three survey boundary points first"
        }
        val west = roi.minOf { it.longitude }
        val east = roi.maxOf { it.longitude }
        val south = roi.minOf { it.latitude }
        val north = roi.maxOf { it.latitude }
        require(west >= -180.0 && east <= 180.0 && south >= -90.0 && north <= 90.0) {
            context?.getString(R.string.building_height_coordinates_outside_wgs84)
                ?: "Building-height request coordinates are outside the WGS84 range"
        }
        require(east > west && north > south) {
            context?.getString(R.string.building_height_area_invalid) ?: "Invalid building-height request area"
        }
        require(east - west <= MAXIMUM_ROI_SPAN_DEGREES &&
            north - south <= MAXIMUM_ROI_SPAN_DEGREES) {
            context?.getString(R.string.building_height_area_too_large)
                ?: "Building-height request exceeds 0.25°; reduce the survey area"
        }
        fun xIndex(longitude: Double) = floor((longitude + 180.0) / TILE_SPAN_DEGREES).toInt()
            .coerceIn(0, 1799)
        fun yIndex(latitude: Double) = floor((latitude + 90.0) / TILE_SPAN_DEGREES).toInt()
            .coerceIn(0, 899)
        val tiles = mutableListOf<BuildingHeightTile>()
        for (y in yIndex(south)..yIndex(north)) for (x in xIndex(west)..xIndex(east)) {
            val tileWest = -180.0 + x * TILE_SPAN_DEGREES
            val tileSouth = -90.0 + y * TILE_SPAN_DEGREES
            tiles += BuildingHeightTile(
                x, y, tileWest, tileSouth,
                tileWest + TILE_SPAN_DEGREES,
                tileSouth + TILE_SPAN_DEGREES,
            )
        }
        require(tiles.size <= MAXIMUM_TILE_COUNT) {
            context?.getString(R.string.building_height_too_many_tiles)
                ?: "The survey area crosses too many building-height tiles"
        }
        return tiles
    }

    fun url(template: String, tile: BuildingHeightTile, context: Context? = null): String {
        require(template.startsWith("https://") || template.startsWith("http://")) {
            context?.getString(R.string.building_cog_template_http_only)
                ?: "Building-height COG template must use HTTP(S)"
        }
        require(template.contains("{x}") && template.contains("{y}")) {
            context?.getString(R.string.building_cog_template_xy_required)
                ?: "Building-height COG template must contain {x} and {y}"
        }
        fun coordinate(value: Double) = String.format(Locale.US, "%.1f", value)
        return template
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
            .replace("{west}", coordinate(tile.west))
            .replace("{south}", coordinate(tile.south))
            .replace("{east}", coordinate(tile.east))
            .replace("{north}", coordinate(tile.north))
    }
}

/** Samples a set of adjacent static GeoTIFF/COG files as one height layer. */
class TiledTerrainElevationSource(
    private val tiles: List<GeoTiffTerrain>,
    displayName: String,
    private val context: Context? = null,
) : TerrainElevationSource {
    init {
        require(tiles.isNotEmpty()) {
            context?.getString(R.string.building_height_tiles_empty) ?: "Building-height tiles are empty"
        }
    }

    override val info = TerrainRasterInfo(
        displayName = displayName,
        width = tiles.sumOf { it.info.width },
        height = tiles.maxOf { it.info.height },
        epsg = 4326,
        noDataValue = null,
        pixelSizeX = tiles.minOf { it.info.pixelSizeX },
        pixelSizeY = tiles.minOf { it.info.pixelSizeY },
        minimumLatitude = tiles.minOf { it.info.minimumLatitude },
        maximumLatitude = tiles.maxOf { it.info.maximumLatitude },
        minimumLongitude = tiles.minOf { it.info.minimumLongitude },
        maximumLongitude = tiles.maxOf { it.info.maximumLongitude },
    )

    override fun elevationMeters(latitude: Double, longitude: Double): Double {
        val candidates = tiles.filter { tile ->
            latitude in tile.info.minimumLatitude..tile.info.maximumLatitude &&
                longitude in tile.info.minimumLongitude..tile.info.maximumLongitude
        }
        require(candidates.isNotEmpty()) {
            context?.getString(R.string.coordinate_outside_building_tiles)
                ?: "Coordinate is outside the building-height tile coverage"
        }
        var lastError: Throwable? = null
        for (tile in candidates) {
            try {
                return tile.elevationMeters(latitude, longitude)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalArgumentException(
            context?.getString(R.string.building_tile_nodata_at_target)
                ?: "Building-height tiles contain NoData at the target location",
            lastError,
        )
    }
}

class GlobalBuildingHeightDownloader private constructor(
    private val cacheRoot: File,
    private val cogUrlTemplate: String,
    private val context: Context?,
) {
    constructor(context: Context, cogUrlTemplate: String) : this(
        File(context.filesDir, "global-terrain/building-height-cog-v1"),
        cogUrlTemplate,
        context.applicationContext,
    )

    internal constructor(cacheRoot: File, cogUrlTemplate: String, forTest: Unit = Unit) :
        this(cacheRoot, cogUrlTemplate, null)

    fun download(roi: List<GeoPoint>): BuildingHeightDownloadResult {
        require(cogUrlTemplate.isNotBlank()) {
            message(R.string.building_cog_url_not_configured, "Static building-height COG URL is not configured")
        }
        check(cacheRoot.exists() || cacheRoot.mkdirs()) {
            message(R.string.building_height_cache_create_failed, "Cannot create the building-height cache directory")
        }
        val rasters = mutableListOf<GeoTiffTerrain>()
        val digests = mutableListOf<String>()
        val versions = mutableListOf<String>()
        val cacheFiles = mutableListOf<File>()
        var byteCount = 0L
        var allCached = true
        for (tile in GlobalBuildingHeightTiles.covering(roi, context)) {
            val requestUrl = GlobalBuildingHeightTiles.url(cogUrlTemplate, tile, context)
            val key = sha256(requestUrl.toByteArray()).take(24)
            val cache = File(cacheRoot, "$key.tif")
            val metadata = File(cacheRoot, "$key.version")
            val cached = cache.isFile && cache.length() > 0L
            val bytes: ByteArray
            if (cached) {
                bytes = cache.inputStream().buffered().use {
                    TerrainImportSafety.readBounded(it, MAXIMUM_BUILDING_TILE_BYTES, context)
                }
                requireTotalSize(byteCount, bytes.size)
                metadata.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
                    ?.let(versions::add)
            } else {
                val response = fetch(requestUrl)
                bytes = response.first
                requireTotalSize(byteCount, bytes.size)
                response.second?.let { version ->
                    versions += version
                    metadata.writeText(version)
                }
                val partial = File(cacheRoot, "$key.partial")
                partial.writeBytes(bytes)
                check(partial.renameTo(cache)) {
                    message(R.string.building_height_cache_commit_failed, "Cannot commit the building-height cache")
                }
            }
            allCached = allCached && cached
            cacheFiles += cache
            byteCount += bytes.size
            digests += sha256(bytes)
            rasters += GeoTiffTerrain.read(
                ByteArrayInputStream(bytes),
                "GlobalBuildingAtlas Height x=${tile.x} y=${tile.y}",
                context,
            )
        }
        val terrain = TiledTerrainElevationSource(
            rasters,
            message(R.string.global_building_atlas_static_cog, "GlobalBuildingAtlas Height static COG"),
            context,
        )
        require(roi.all { point ->
            point.latitude in terrain.info.minimumLatitude..terrain.info.maximumLatitude &&
                point.longitude in terrain.info.minimumLongitude..terrain.info.maximumLongitude
        }) {
            message(R.string.building_cog_incomplete_coverage, "Building-height COG does not fully cover the survey area")
        }
        return BuildingHeightDownloadResult(
            terrain,
            sha256(digests.sorted().joinToString("").toByteArray()),
            byteCount,
            allCached,
            versions.distinct().sorted().joinToString().ifEmpty { null },
            rasters.size,
            cacheFiles,
        )
    }

    private fun fetch(requestUrl: String): Pair<ByteArray, String?> {
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "image/tiff,application/octet-stream")
        connection.setRequestProperty("User-Agent", "DJI-VLN-StaticSurface/1.0")
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                message(
                    R.string.building_cog_http_failed,
                    "Static building-height COG HTTP ${connection.responseCode}",
                    connection.responseCode,
                )
            }
            val contentLength = connection.contentLengthLong
            require(contentLength < 0L || contentLength <= MAXIMUM_BUILDING_TILE_BYTES) {
                message(
                    R.string.building_tile_too_large,
                    "A building-height tile exceeds ${MAXIMUM_BUILDING_TILE_BYTES / (1024L * 1024L)} MB",
                    MAXIMUM_BUILDING_TILE_BYTES / (1024L * 1024L),
                )
            }
            connection.inputStream.use {
                TerrainImportSafety.readBounded(it, MAXIMUM_BUILDING_TILE_BYTES, context)
            } to (
                connection.getHeaderField("ETag") ?: connection.getHeaderField("Last-Modified")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun requireTotalSize(currentBytes: Long, nextBytes: Int) {
        require(currentBytes + nextBytes <= TerrainImportSafety.MAX_IMPORT_BYTES) {
            message(
                R.string.building_tiles_total_too_large,
                "Building-height tiles exceed ${TerrainImportSafety.MAX_IMPORT_BYTES / (1024L * 1024L)} MB in total; reduce the survey area",
                TerrainImportSafety.MAX_IMPORT_BYTES / (1024L * 1024L),
            )
        }
    }

    private fun message(resourceId: Int, fallback: String, vararg arguments: Any): String =
        context?.getString(resourceId, *arguments) ?: fallback

    private companion object {
        const val MAXIMUM_BUILDING_TILE_BYTES = 32L * 1024L * 1024L
    }
}
