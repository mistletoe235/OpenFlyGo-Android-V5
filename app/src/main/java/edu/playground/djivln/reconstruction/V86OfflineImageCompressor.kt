package edu.playground.djivln.reconstruction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import edu.playground.djivln.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import org.json.JSONObject
import kotlin.math.max

class V86OfflineImageCompressor(
    private val cacheDirectory: File? = null,
    private val context: Context? = null,
) {
    data class Inspection(
        val latitude: Double,
        val longitude: Double,
        val absoluteAltitudeMeters: Double?,
        val timestamp: String?,
        val make: String?,
        val model: String?,
        val relativeAltitudeMeters: Double? = null,
        val preservedAttributes: Map<String, String> = emptyMap(),
    )

    data class PreparedImage(
        val displayName: String,
        val jpeg: ByteArray,
        val originalBytes: Int,
        val width: Int,
        val height: Int,
        val latitude: Double,
        val longitude: Double,
        val absoluteAltitudeMeters: Double?,
        val timestamp: String?,
        val captureView: String,
        val relativeAltitudeMeters: Double? = null,
        val relativeHeightTest: Boolean = false,
    )

    fun prepare(displayName: String, input: InputStream, relativeHeightTest: Boolean = false,
                captureViewOverride: String? = null): PreparedImage {
        val original = input.readBounded(MAX_INPUT_BYTES)
        val inspection = inspect(displayName, original, relativeHeightTest)
        val compressed = if (original.size <= TARGET_BYTES) {
            original
        } else {
            val xmp = JpegApp1Preserver.extractXmp(original)
            val pixels = compress(original, xmp.sumOf { it.size } + SANITIZED_EXIF_BUDGET_BYTES)
            val withExif = writeSanitizedExif(pixels, inspection, displayName)
            JpegApp1Preserver.injectAfterExif(withExif, xmp)
        }
        require(compressed.size <= MAX_OUTPUT_BYTES) {
            message(
                R.string.v86_image_still_too_large,
                "$displayName remains ${compressed.size / 1024} KiB after compression, above the ${MAX_OUTPUT_BYTES / 1024} KiB safety limit",
                displayName,
                compressed.size / 1024,
                MAX_OUTPUT_BYTES / 1024,
            )
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(compressed, 0, compressed.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            message(R.string.v86_image_decode_after_compress_failed, "$displayName cannot be decoded after compression", displayName)
        }
        return PreparedImage(
            displayName = displayName.substringBeforeLast('.') + ".jpg",
            jpeg = compressed,
            originalBytes = original.size,
            width = bounds.outWidth,
            height = bounds.outHeight,
            latitude = inspection.latitude,
            longitude = inspection.longitude,
            absoluteAltitudeMeters = inspection.absoluteAltitudeMeters,
            timestamp = inspection.timestamp,
            captureView = captureViewOverride ?: inferCaptureView(displayName),
            relativeAltitudeMeters = inspection.relativeAltitudeMeters,
            relativeHeightTest = relativeHeightTest,
        )
    }

    fun validate(displayName: String, input: InputStream, relativeHeightTest: Boolean = false): Inspection {
        val prefix = input.readPrefix(METADATA_PREFIX_BYTES)
        require(prefix.isJpeg()) {
            message(R.string.v86_image_not_jpeg, "$displayName is not JPEG; offline EXIF replay accepts JPG/JPEG only", displayName)
        }
        return inspect(displayName, prefix, relativeHeightTest)
    }

    private fun inspect(displayName: String, original: ByteArray, relativeHeightTest: Boolean): Inspection {
        require(original.isJpeg()) {
            message(R.string.v86_image_not_jpeg, "$displayName is not JPEG; offline EXIF replay accepts JPG/JPEG only", displayName)
        }
        val exif = runCatching { ExifInterface(ByteArrayInputStream(original)) }.getOrNull()
        val djiXmp = DjiXmpMetadata.parse(original.copyOfRange(0, minOf(original.size, METADATA_PREFIX_BYTES)))
        val location = exif?.latLong
        val latitude = djiXmp?.latitude ?: location?.get(0)
        val longitude = djiXmp?.longitude ?: location?.get(1)
        val exifAltitude = exif?.getAltitude(Double.NaN)?.takeIf(Double::isFinite)
        val altitude = djiXmp?.absoluteAltitudeMeters ?: exifAltitude
        val comment = exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)
        val openfly = comment?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("source") == "dji_video_downlink" }
        val relative = openfly?.takeIf { !it.isNull("relative_altitude_m") }
            ?.optDouble("relative_altitude_m")?.takeIf(Double::isFinite)
        require(latitude != null && longitude != null) {
            message(R.string.v86_image_gps_missing, "$displayName is missing EXIF/DJI XMP GPS", displayName)
        }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            message(R.string.v86_image_gps_out_of_range, "$displayName GPS is out of range", displayName)
        }
        require(if (relativeHeightTest) relative != null else altitude?.isFinite() == true) {
            if (relativeHeightTest) message(R.string.v86_relative_metadata_missing, "OpenFly relative altitude metadata is missing: $displayName", displayName)
            else message(R.string.v86_image_absolute_altitude_missing, "$displayName is missing EXIF/DJI XMP absolute altitude", displayName)
        }
        return Inspection(
            latitude = latitude,
            longitude = longitude,
            absoluteAltitudeMeters = altitude.takeUnless { relativeHeightTest },
            timestamp = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
                ?: djiXmp?.timestamp,
            make = exif?.getAttribute(ExifInterface.TAG_MAKE) ?: djiXmp?.make,
            model = exif?.getAttribute(ExifInterface.TAG_MODEL) ?: djiXmp?.model,
            relativeAltitudeMeters = relative,
            preservedAttributes = listOf(
                ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_GPS_IMG_DIRECTION,
                ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_SPEED,
                ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_TRACK,
                ExifInterface.TAG_GPS_TRACK_REF,
            ).mapNotNull { tag -> exif?.getAttribute(tag)?.let { tag to it } }.toMap(),
        )
    }

    private fun compress(original: ByteArray, metadataOverheadBytes: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            message(R.string.jpeg_cannot_decode, "JPEG cannot be decoded")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_DIMENSION) sample *= 2
        var bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(
                original,
                0,
                original.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ),
        ) { message(R.string.jpeg_decode_failed, "JPEG decoding failed") }
        try {
            repeat(MAX_SCALE_PASSES) {
                val encoded = bestQuality(bitmap, metadataOverheadBytes)
                val estimatedSize = encoded.size + metadataOverheadBytes
                if (estimatedSize <= TARGET_BYTES || minOf(bitmap.width, bitmap.height) <= MIN_DIMENSION) {
                    return encoded
                }
                val ratio = kotlin.math.sqrt(TARGET_BYTES.toDouble() / estimatedSize.toDouble())
                    .coerceIn(0.65, 0.90)
                val nextWidth = (bitmap.width * ratio).toInt().coerceAtLeast(MIN_DIMENSION)
                val nextHeight = (bitmap.height * ratio).toInt().coerceAtLeast(MIN_DIMENSION)
                val scaled = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            return bestQuality(bitmap, metadataOverheadBytes)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun bestQuality(bitmap: Bitmap, metadataOverheadBytes: Int): ByteArray {
        var low = MIN_JPEG_QUALITY
        var high = MAX_JPEG_QUALITY
        var best: ByteArray? = null
        var smallest: ByteArray? = null
        while (low <= high) {
            val quality = (low + high) / 2
            val raw = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    message(R.string.jpeg_encode_failed, "JPEG encoding failed")
                }
                output.toByteArray()
            }
            if (smallest == null || raw.size < requireNotNull(smallest).size) smallest = raw
            if (raw.size + metadataOverheadBytes <= TARGET_BYTES) {
                best = raw
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        return best ?: requireNotNull(smallest)
    }

    private fun writeSanitizedExif(jpeg: ByteArray, inspection: Inspection, displayName: String): ByteArray {
        val directory = requireNotNull(cacheDirectory) {
            message(R.string.exif_temp_directory_missing, "EXIF temporary directory is missing")
        }
        require(directory.mkdirs() || directory.isDirectory) {
            message(R.string.exif_temp_directory_create_failed, "Cannot create the EXIF temporary directory")
        }
        val temporary = File.createTempFile("v86-offline-", ".jpg", directory)
        return try {
            temporary.writeBytes(jpeg)
            ExifInterface(temporary).apply {
                setLatLong(inspection.latitude, inspection.longitude)
                inspection.absoluteAltitudeMeters?.let(::setAltitude)
                if (inspection.absoluteAltitudeMeters != null && inspection.absoluteAltitudeMeters >= 0.0) {
                    // GPSAltitudeRef is optional for above-sea-level values. Pillow exposes the
                    // legal TIFF BYTE as b'\u0000', while the deployed V86 parser currently calls
                    // int(value) directly; omitting the default-zero tag avoids that parser bug.
                    setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                }
                inspection.make?.let { setAttribute(ExifInterface.TAG_MAKE, it) }
                inspection.model?.let { setAttribute(ExifInterface.TAG_MODEL, it) }
                normalizedExifDateTime(inspection.timestamp)?.let { timestamp ->
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, timestamp)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, timestamp)
                }
                timestampOffset(inspection.timestamp)?.let { offset ->
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
                }
                inspection.preservedAttributes.forEach { (tag, value) -> setAttribute(tag, value) }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_SOFTWARE, "OpenFly Go V5 offline replay")
                setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "$displayName compressed for V86; original DJI XMP preserved",
                )
                saveAttributes()
            }
            temporary.readBytes()
        } finally {
            temporary.delete()
        }
    }

    private fun normalizedExifDateTime(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(text.take(19))) {
            return text.take(19)
        }
        if (Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}").matches(text.take(19))) {
            return text.take(19).replaceRange(4, 5, ":").replaceRange(7, 8, ":").replace('T', ' ')
        }
        return null
    }

    private fun timestampOffset(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.endsWith('Z')) return "+00:00"
        return Regex("[+-]\\d{2}:\\d{2}$").find(text)?.value
    }

    private fun message(resourceId: Int, fallback: String, vararg arguments: Any): String =
        context?.getString(resourceId, *arguments) ?: fallback

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) {
                message(
                    R.string.image_exceeds_size_limit,
                    "Image exceeds the ${maxBytes / 1024 / 1024} MiB limit",
                    maxBytes / 1024 / 1024,
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        const val TARGET_BYTES = 600 * 1024
        const val MAX_OUTPUT_BYTES = 640 * 1024
        const val MAX_INPUT_BYTES = 48 * 1024 * 1024
        private const val METADATA_PREFIX_BYTES = 256 * 1024
        private const val SANITIZED_EXIF_BUDGET_BYTES = 4 * 1024
        private const val MAX_DECODE_DIMENSION = 2_560
        private const val MIN_DIMENSION = 640
        private const val MIN_JPEG_QUALITY = 35
        private const val MAX_JPEG_QUALITY = 94
        private const val MAX_SCALE_PASSES = 4

        private fun inferCaptureView(name: String): String {
            val normalized = name.lowercase(Locale.US)
            return when {
                "backward" in normalized || "_back_" in normalized -> "BACKWARD_OBLIQUE"
                "forward" in normalized || "_front_" in normalized -> "FORWARD_OBLIQUE"
                "left" in normalized -> "LEFT_OBLIQUE"
                "right" in normalized -> "RIGHT_OBLIQUE"
                else -> "NADIR"
            }
        }

        private fun InputStream.readPrefix(maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            return output.toByteArray()
        }

        private fun ByteArray.isJpeg(): Boolean = size >= 4 &&
            this[0] == 0xff.toByte() && this[1] == 0xd8.toByte()
    }
}

private data class DjiXmpMetadata(
    val latitude: Double,
    val longitude: Double,
    val absoluteAltitudeMeters: Double,
    val timestamp: String?,
    val make: String?,
    val model: String?,
) {
    companion object {
        fun parse(prefix: ByteArray): DjiXmpMetadata? {
            val text = prefix.toString(Charsets.ISO_8859_1)
            fun attribute(name: String): String? = Regex(
                "${Regex.escape(name)}\\s*=\\s*[\"']([^\"']+)[\"']",
            ).find(text)?.groupValues?.get(1)
            val latitude = attribute("drone-dji:GpsLatitude")?.toDoubleOrNull() ?: return null
            val longitude = attribute("drone-dji:GpsLongitude")?.toDoubleOrNull() ?: return null
            val altitude = attribute("drone-dji:AbsoluteAltitude")?.toDoubleOrNull() ?: return null
            return DjiXmpMetadata(
                latitude = latitude,
                longitude = longitude,
                absoluteAltitudeMeters = altitude,
                timestamp = attribute("xmp:CreateDate") ?: attribute("xmp:ModifyDate"),
                make = "DJI",
                model = attribute("drone-dji:DroneModel"),
            )
        }
    }
}

/** Preserves raw APP1 blocks, including DJI EXIF/MakerNote and optional XMP, byte-for-byte. */
internal object JpegApp1Preserver {
    fun extract(jpeg: ByteArray): List<ByteArray> {
        require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val result = mutableListOf<ByteArray>()
        var offset = 2
        while (offset + 4 <= jpeg.size) {
            if (jpeg[offset] != 0xff.toByte()) break
            val marker = jpeg[offset + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            if (marker == 0x01 || marker in 0xd0..0xd7) {
                offset += 2
                continue
            }
            val length = ((jpeg[offset + 2].toInt() and 0xff) shl 8) or
                (jpeg[offset + 3].toInt() and 0xff)
            require(length >= 2 && offset + 2 + length <= jpeg.size) { "Invalid JPEG marker length" }
            val end = offset + 2 + length
            if (marker == 0xe1) result += jpeg.copyOfRange(offset, end)
            offset = end
        }
        return result
    }

    fun inject(encoded: ByteArray, app1: List<ByteArray>): ByteArray {
        if (app1.isEmpty()) return encoded
        require(encoded.size >= 2 && encoded[0] == 0xff.toByte() && encoded[1] == 0xd8.toByte())
        return ByteArrayOutputStream(encoded.size + app1.sumOf { it.size }).use { output ->
            output.write(encoded, 0, 2)
            app1.forEach { output.write(it) }
            output.write(encoded, 2, encoded.size - 2)
            output.toByteArray()
        }
    }

    fun extractXmp(jpeg: ByteArray): List<ByteArray> = extract(jpeg).filter { segment ->
        segment.size > 4 && segment.copyOfRange(4, minOf(segment.size, 96))
            .toString(Charsets.ISO_8859_1)
            .contains("http://ns.adobe.com/xap/1.0/")
    }

    fun injectAfterExif(encoded: ByteArray, app1: List<ByteArray>): ByteArray {
        if (app1.isEmpty()) return encoded
        require(encoded.isJpeg())
        var offset = 2
        var insertOffset = 2
        while (offset + 4 <= encoded.size && encoded[offset] == 0xff.toByte()) {
            val marker = encoded[offset + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            if (marker == 0x01 || marker in 0xd0..0xd7) {
                offset += 2
                continue
            }
            val length = ((encoded[offset + 2].toInt() and 0xff) shl 8) or
                (encoded[offset + 3].toInt() and 0xff)
            if (length < 2 || offset + 2 + length > encoded.size) break
            val end = offset + 2 + length
            if (marker == 0xe0 || marker == 0xe1) insertOffset = end
            offset = end
        }
        return ByteArrayOutputStream(encoded.size + app1.sumOf { it.size }).use { output ->
            output.write(encoded, 0, insertOffset)
            app1.forEach { output.write(it) }
            output.write(encoded, insertOffset, encoded.size - insertOffset)
            output.toByteArray()
        }
    }

    private fun ByteArray.isJpeg(): Boolean = size >= 4 &&
        this[0] == 0xff.toByte() && this[1] == 0xd8.toByte()
}
