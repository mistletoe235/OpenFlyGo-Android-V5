package edu.playground.djivln.camera

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.playground.djivln.survey.SurveyFrameMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class SurveyFrameExifWriterInstrumentedTest {
    @Test
    fun writesAndReadsAircraftGpsAndFrameTime() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jpeg = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).run {
            try {
                ByteArrayOutputStream().use { output ->
                    assertTrue(compress(Bitmap.CompressFormat.JPEG, 90, output))
                    output.toByteArray()
                }
            } finally {
                recycle()
            }
        }
        val tagged = SurveyFrameExifWriter(context.cacheDir).write(
            jpeg,
            SurveyFrameMetadata(
                frameEpochMillis = 1_700_000_000_350L,
                latitude = 31.1815535,
                longitude = 121.4736515,
                altitudeAboveSeaLevelMeters = 18.5,
                relativeAltitudeMeters = 7.2,
                altitudeAboveGroundMeters = 6.8,
                headingDegrees = 350.0,
                rollDegrees = 1.5,
                pitchDegrees = -3.0,
                yawDegrees = 340.0,
                velocityNorthMetersPerSecond = 3.0,
                velocityEastMetersPerSecond = 4.0,
                velocityUpMetersPerSecond = 0.5,
                groundSpeedMetersPerSecond = 5.0,
                groundTrackDegrees = 53.130102,
                gimbalPitchDegrees = -80.0,
                gpsSatelliteCount = 18,
                gpsSignalLevel = "LEVEL_5",
                gpsAgeMillis = 60L,
                headingAgeMillis = 40L,
                attitudeAgeMillis = 50L,
                velocityAgeMillis = 30L,
                frameAfterTriggerMillis = 350L,
                telemetryAfterFrameMillis = 10L,
                altitudeAboveSeaLevelSource = "takeoff_asl_plus_relative",
            ),
        )

        val exif = ExifInterface(ByteArrayInputStream(tagged))
        val latLong = requireNotNull(exif.latLong)
        assertEquals(31.1815535, latLong[0], 1e-6)
        assertEquals(121.4736515, latLong[1], 1e-6)
        assertEquals(18.5, exif.getAltitude(Double.NaN), 0.01)
        assertEquals(350.0, exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN), 0.001)
        assertEquals(18.0, exif.getAttributeDouble(ExifInterface.TAG_GPS_SPEED, Double.NaN), 0.001)
        assertEquals(53.13, exif.getAttributeDouble(ExifInterface.TAG_GPS_TRACK, Double.NaN), 0.001)
        assertEquals("350", exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
        assertTrue(
            exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                .orEmpty()
                .contains("\"gps_source\":\"aircraft_telemetry\""),
        )
        assertTrue(exif.getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty().contains("\"yaw_deg\":340"))
        assertTrue(exif.getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty().contains("\"gimbal_pitch_deg\":-80"))
        assertTrue(
            exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                .orEmpty()
                .contains("\"altitude_asl_source\":\"takeoff_asl_plus_relative\""),
        )
    }
}
