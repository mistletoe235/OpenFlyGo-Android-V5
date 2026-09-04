package edu.playground.djivln.survey

import android.content.Context
import android.graphics.BitmapFactory
import edu.playground.djivln.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

data class WebMercatorTileId(val zoom: Int, val x: Int, val y: Int) {
    init {
        require(zoom in 0..15)
        val count = 1 shl zoom
        require(x in 0 until count && y in 0 until count)
    }

    val cacheName: String get() = "$zoom-$x-$y.png"
}

object WebMercatorTileGrid {
    const val TILE_SIZE = 256
    const val MAX_LATITUDE = 85.05112878

    fun tileIdsForRoi(
        roi: List<GeoPoint>,
        zoom: Int,
        maximumTiles: Int = 64,
        context: Context? = null,
    ): List<WebMercatorTileId> {
        require(roi.size >= 3) {
            context?.getString(R.string.draw_three_roi_points_first) ?: "Draw at least three survey boundary points first"
        }
        require(zoom in 0..15)
        val minLon = roi.minOf { it.longitude }
        val maxLon = roi.maxOf { it.longitude }
        require(maxLon - minLon < 180.0) {
            context?.getString(R.string.roi_dateline_unsupported) ?: "Survey areas crossing the international date line are not supported"
        }
        val minLat = roi.minOf { it.latitude }.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val maxLat = roi.maxOf { it.latitude }.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val firstX = tileX(minLon, zoom)
        val lastX = tileX(maxLon, zoom)
        val firstY = tileY(maxLat, zoom)
        val lastY = tileY(minLat, zoom)
        val count = (lastX - firstX + 1) * (lastY - firstY + 1)
        require(count in 1..maximumTiles) {
            context?.getString(R.string.global_terrain_too_many_tiles, count, maximumTiles)
                ?: "Global terrain requires $count tiles, above the $maximumTiles limit; reduce the survey area"
        }
        return buildList {
            for (y in firstY..lastY) for (x in firstX..lastX) {
                add(WebMercatorTileId(zoom, x, y))
            }
        }
    }

    fun tileX(longitude: Double, zoom: Int): Int {
        val count = 1 shl zoom
        return floor((longitude.coerceIn(-180.0, 180.0 - 1e-12) + 180.0) /
            360.0 * count).toInt().coerceIn(0, count - 1)
    }

    fun tileY(latitude: Double, zoom: Int): Int {
        val count = 1 shl zoom
        val lat = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return floor((1.0 - asinh(tan(lat)) / PI) / 2.0 * count)
            .toInt().coerceIn(0, count - 1)
    }

    fun globalPixel(longitude: Double, latitude: Double, zoom: Int): Pair<Double, Double> {
        val size = TILE_SIZE.toDouble() * (1 shl zoom)
        val x = (longitude + 180.0) / 360.0 * size
        val lat = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        val y = (1.0 - asinh(tan(lat)) / PI) / 2.0 * size
        return x to y
    }

    fun longitudeAtTileX(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

    fun latitudeAtTileY(y: Int, zoom: Int): Double {
        val mercator = PI * (1.0 - 2.0 * y.toDouble() / (1 shl zoom))
        return Math.toDegrees(kotlin.math.atan(sinh(mercator)))
    }
}

data class TerrariumTile(
    val id: WebMercatorTileId,
    val argb: IntArray,
) {
    init {
        require(argb.size == WebMercatorTileGrid.TILE_SIZE * WebMercatorTileGrid.TILE_SIZE)
    }

    fun elevationAt(x: Int, y: Int): Double = decodeElevationMeters(
        argb[y * WebMercatorTileGrid.TILE_SIZE + x],
    )

    companion object {
        fun decodeElevationMeters(argb: Int): Double {
            val red = argb ushr 16 and 0xff
            val green = argb ushr 8 and 0xff
            val blue = argb and 0xff
            return red * 256.0 + green + blue / 256.0 - 32768.0
        }
    }
}

class TerrariumTerrain(
    private val zoom: Int,
    tiles: Collection<TerrariumTile>,
    displayName: String = "Global bare-earth DEM (Mapzen/AWS)",
    private val context: Context? = null,
) : TerrainElevationSource {
    private val tilesById = tiles.associateBy { it.id }
    private val minX = tilesById.keys.minOf { it.x }
    private val maxX = tilesById.keys.maxOf { it.x }
    private val minY = tilesById.keys.minOf { it.y }
    private val maxY = tilesById.keys.maxOf { it.y }

    override val info: TerrainRasterInfo

    init {
        require(tilesById.isNotEmpty()) {
            context?.getString(R.string.terrain_tiles_missing) ?: "No terrain tiles"
        }
        require(tilesById.keys.all { it.zoom == zoom }) {
            context?.getString(R.string.terrain_tile_zoom_mismatch) ?: "Terrain tile zoom levels do not match"
        }
        val minLongitude = WebMercatorTileGrid.longitudeAtTileX(minX, zoom)
        val maxLongitude = WebMercatorTileGrid.longitudeAtTileX(maxX + 1, zoom)
        val maximumLatitude = WebMercatorTileGrid.latitudeAtTileY(minY, zoom)
        val minimumLatitude = WebMercatorTileGrid.latitudeAtTileY(maxY + 1, zoom)
        val width = (maxX - minX + 1) * WebMercatorTileGrid.TILE_SIZE
        val height = (maxY - minY + 1) * WebMercatorTileGrid.TILE_SIZE
        info = TerrainRasterInfo(
            displayName = displayName,
            width = width,
            height = height,
            epsg = 4326,
            noDataValue = null,
            pixelSizeX = (maxLongitude - minLongitude) / width,
            pixelSizeY = (maximumLatitude - minimumLatitude) / height,
            minimumLatitude = minimumLatitude,
            maximumLatitude = maximumLatitude,
            minimumLongitude = minLongitude,
            maximumLongitude = maxLongitude,
        )
    }

    override fun elevationMeters(latitude: Double, longitude: Double): Double {
        require(latitude in info.minimumLatitude..info.maximumLatitude &&
            longitude in info.minimumLongitude..info.maximumLongitude) {
            context?.getString(R.string.coordinate_outside_downloaded_terrain)
                ?: "Coordinate is outside the downloaded global terrain"
        }
        // Keep inclusive geographic bounds from sampling the first pixel of an
        // adjacent tile that was not part of this download.
        val sampledLongitude = longitude.coerceIn(
            info.minimumLongitude,
            Math.nextDown(info.maximumLongitude),
        )
        val sampledLatitude = latitude.coerceIn(
            Math.nextUp(info.minimumLatitude),
            info.maximumLatitude,
        )
        val rawPixel = WebMercatorTileGrid.globalPixel(sampledLongitude, sampledLatitude, zoom)
        val pixelX = rawPixel.first.coerceIn(
            minX * WebMercatorTileGrid.TILE_SIZE.toDouble(),
            (maxX + 1) * WebMercatorTileGrid.TILE_SIZE.toDouble() - 1.000001,
        )
        val pixelY = rawPixel.second.coerceIn(
            minY * WebMercatorTileGrid.TILE_SIZE.toDouble(),
            (maxY + 1) * WebMercatorTileGrid.TILE_SIZE.toDouble() - 1.000001,
        )
        val x0 = floor(pixelX).toInt()
        val y0 = floor(pixelY).toInt()
        val tx = pixelX - x0
        val ty = pixelY - y0
        val q00 = sampleGlobalPixel(x0, y0)
        val q10 = sampleGlobalPixel(x0 + 1, y0)
        val q01 = sampleGlobalPixel(x0, y0 + 1)
        val q11 = sampleGlobalPixel(x0 + 1, y0 + 1)
        return (q00 * (1.0 - tx) + q10 * tx) * (1.0 - ty) +
            (q01 * (1.0 - tx) + q11 * tx) * ty
    }

    private fun sampleGlobalPixel(globalX: Int, globalY: Int): Double {
        val tileX = Math.floorDiv(globalX, WebMercatorTileGrid.TILE_SIZE)
        val tileY = Math.floorDiv(globalY, WebMercatorTileGrid.TILE_SIZE)
        val localX = Math.floorMod(globalX, WebMercatorTileGrid.TILE_SIZE)
        val localY = Math.floorMod(globalY, WebMercatorTileGrid.TILE_SIZE)
        val tile = tilesById[WebMercatorTileId(zoom, tileX, tileY)]
            ?: error(
                context?.getString(R.string.terrain_tile_edge_missing, zoom, tileX, tileY)
                    ?: "Terrain tile edge is missing: $zoom/$tileX/$tileY",
            )
        return tile.elevationAt(localX, localY)
    }
}

data class GlobalTerrainDownloadResult(
    val terrain: TerrariumTerrain,
    val sha256: String,
    val downloadedTiles: Int,
    val cachedTiles: Int,
    val imagerySources: Set<String>,
)

class GlobalTerrainDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.filesDir, "global-terrain/terrarium-v1")

    fun download(
        roi: List<GeoPoint>,
        zoom: Int = 14,
        progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): GlobalTerrainDownloadResult {
        val ids = WebMercatorTileGrid.tileIdsForRoi(roi, zoom, context = appContext)
        check(cacheRoot.exists() || cacheRoot.mkdirs()) {
            appContext.getString(R.string.global_terrain_cache_create_failed)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val tiles = ArrayList<TerrariumTile>(ids.size)
        val sources = linkedSetOf<String>()
        var downloaded = 0
        var cached = 0
        ids.forEachIndexed { index, id ->
            val file = File(cacheRoot, id.cacheName)
            val bytes: ByteArray
            if (file.isFile && file.length() > 0L) {
                bytes = file.inputStream().buffered().use {
                    TerrainImportSafety.readBounded(it, MAXIMUM_TERRARIUM_TILE_BYTES, appContext)
                }
                cached++
            } else {
                val response = fetch(id)
                bytes = response.first
                response.second?.split(',')?.mapTo(sources) { it.trim() }
                val partial = File(cacheRoot, id.cacheName + ".partial")
                FileOutputStream(partial).use { it.write(bytes) }
                check(partial.renameTo(file)) {
                    appContext.getString(R.string.terrain_cache_commit_failed, id.cacheName)
                }
                downloaded++
            }
            digest.update(id.cacheName.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
            tiles += decode(id, bytes)
            progress(index + 1, ids.size)
        }
        return GlobalTerrainDownloadResult(
            terrain = TerrariumTerrain(
                zoom,
                tiles,
                appContext.getString(R.string.global_bare_earth_dem),
                appContext,
            ),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            downloadedTiles = downloaded,
            cachedTiles = cached,
            imagerySources = sources,
        )
    }

    private fun fetch(id: WebMercatorTileId): Pair<ByteArray, String?> {
        val url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/" +
            "${id.zoom}/${id.x}/${id.y}.png"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "DJI-VLN-GlobalTerrain/1.0")
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                appContext.getString(
                    R.string.global_terrain_download_http_failed,
                    connection.responseCode,
                    id.zoom,
                    id.x,
                    id.y,
                )
            }
            connection.inputStream.use {
                TerrainImportSafety.readBounded(it, MAXIMUM_TERRARIUM_TILE_BYTES, appContext)
            } to
                (connection.getHeaderField("X-Imagery-Sources")
                    ?: connection.getHeaderField("x-amz-meta-x-imagery-sources"))
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(id: WebMercatorTileId, bytes: ByteArray): TerrariumTile {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error(appContext.getString(R.string.global_terrain_tile_invalid_png, id.cacheName))
        require(bitmap.width == WebMercatorTileGrid.TILE_SIZE &&
            bitmap.height == WebMercatorTileGrid.TILE_SIZE) {
            appContext.getString(R.string.global_terrain_tile_size_invalid, id.cacheName)
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return TerrariumTile(id, pixels)
    }

    private companion object {
        const val MAXIMUM_TERRARIUM_TILE_BYTES = 4L * 1024L * 1024L
    }
}

object TerrainPreviewSampler {
    fun forArea(
        terrain: TerrainElevationSource,
        roi: List<GeoPoint>,
        columns: Int = 72,
        rows: Int = 48,
        context: Context? = null,
    ): TerrainPreviewData {
        require(roi.size >= 3)
        require(columns in 2..256 && rows in 2..256)
        TerrainImportSafety.requireCompleteCoverage(terrain, roi, context)
        val rawMinLat = roi.minOf { it.latitude }
        val rawMaxLat = roi.maxOf { it.latitude }
        val rawMinLon = roi.minOf { it.longitude }
        val rawMaxLon = roi.maxOf { it.longitude }
        val latMargin = max((rawMaxLat - rawMinLat) * 0.12, 1e-6)
        val lonMargin = max((rawMaxLon - rawMinLon) * 0.12, 1e-6)
        val minLat = max(rawMinLat - latMargin, terrain.info.minimumLatitude)
        val maxLat = min(rawMaxLat + latMargin, terrain.info.maximumLatitude)
        val minLon = max(rawMinLon - lonMargin, terrain.info.minimumLongitude)
        val maxLon = min(rawMaxLon + lonMargin, terrain.info.maximumLongitude)
        require(minLat < maxLat && minLon < maxLon) {
            context?.getString(R.string.roi_outside_terrain_coverage)
                ?: "Survey area is outside terrain-data coverage"
        }
        val values = DoubleArray(columns * rows) { Double.NaN }
        for (row in 0 until rows) for (column in 0 until columns) {
            val latitude = maxLat - row.toDouble() / (rows - 1) * (maxLat - minLat)
            val longitude = minLon + column.toDouble() / (columns - 1) * (maxLon - minLon)
            values[row * columns + column] = runCatching {
                terrain.elevationMeters(latitude, longitude)
            }.getOrDefault(Double.NaN)
        }
        return TerrainPreviewData(
            terrain.info.copy(
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
}
