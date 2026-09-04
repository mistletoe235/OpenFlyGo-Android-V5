package edu.playground.djivln.reconstruction

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Random
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V86RelativeImageInstrumentedTest {
    @Test
    fun compressedRelativeFramePreservesPoseGpsTimeAndNoAsl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "v86-relative-test-source.jpg")
        val bitmap = Bitmap.createBitmap(1400, 1050, Bitmap.Config.ARGB_8888)
        val random = Random(17)
        bitmap.setPixels(IntArray(1400 * 1050) { random.nextInt() or (0xff shl 24) }, 0, 1400, 0, 0, 1400, 1050)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val comment = JSONObject().put("source", "dji_video_downlink")
            .put("relative_altitude_m", 40.0).put("gimbal_pitch_deg", -45.0).put("yaw_deg", 22.0).toString()
        ExifInterface(file).apply {
            setLatLong(31.123456, 121.234567)
            setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:08:30 17:21:54")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+08:00")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "318")
            saveAttributes()
        }
        val originalSize = file.length()
        assertTrue(originalSize > V86OfflineImageCompressor.TARGET_BYTES)
        val compressor = V86OfflineImageCompressor(File(context.cacheDir, "v86-compressor-test"), context)
        val result = file.inputStream().use { compressor.prepare("frame.jpg", it, true, "FORWARD_OBLIQUE") }
        assertTrue(result.jpeg.size <= V86OfflineImageCompressor.MAX_OUTPUT_BYTES)
        assertEquals(originalSize, file.length())
        assertNull(result.absoluteAltitudeMeters)
        assertEquals(40.0, requireNotNull(result.relativeAltitudeMeters), 0.0)
        assertEquals("FORWARD_OBLIQUE", result.captureView)
        val exif = ExifInterface(result.jpeg.inputStream())
        assertTrue(exif.getAltitude(Double.NaN).isNaN())
        assertEquals(31.123456, requireNotNull(exif.latLong)[0], 0.000001)
        assertEquals(121.234567, requireNotNull(exif.latLong)[1], 0.000001)
        assertEquals(comment, exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertEquals("+08:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("318", exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
        assertTrue(file.inputStream().use { runCatching { compressor.validate("frame.jpg", it) }.isFailure })
        file.delete()
    }
}
