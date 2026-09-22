package edu.playground.djivln.camera

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import edu.playground.djivln.R
import edu.playground.djivln.survey.SurveyFrameMetadata
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SurveyFrameExifWriter(
    private val cacheDirectory: File,
    context: Context? = null,
) {
    private val appContext = context?.applicationContext

    fun write(jpeg: ByteArray, metadata: SurveyFrameMetadata): ByteArray {
        require(jpeg.isNotEmpty()) {
            appContext?.getString(R.string.exif_jpeg_empty) ?: "JPEG data is empty"
        }
        require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            appContext?.getString(R.string.exif_temp_directory_create_failed)
                ?: "Cannot create the EXIF temporary directory"
        }
        val temporary = File.createTempFile("survey-frame-", ".jpg", cacheDirectory)
        return try {
            temporary.writeBytes(jpeg)
            ExifInterface(temporary).apply {
                val date = Date(metadata.frameEpochMillis)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, localDateTime(date))
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, localDateTime(date))
                setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsecond(metadata.frameEpochMillis))
                setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subsecond(metadata.frameEpochMillis))
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, localOffset(date))
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, localOffset(date))
                setAttribute(ExifInterface.TAG_SOFTWARE, "OpenFly Go V5")
                setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "DJI video downlink frame; GPS sampled from aircraft telemetry at frame receipt",
                )
                if (metadata.hasFreshAircraftGps) {
                    setLatLong(requireNotNull(metadata.latitude), requireNotNull(metadata.longitude))
                    metadata.altitudeAboveSeaLevelMeters?.let(::setAltitude)
                    metadata.cameraYawDegrees?.let { heading ->
                        setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
                        setAttribute(
                            ExifInterface.TAG_GPS_IMG_DIRECTION,
                            "${kotlin.math.round(heading * HEADING_RATIONAL_SCALE).toLong()}/$HEADING_RATIONAL_SCALE",
                        )
                    }
                    metadata.groundSpeedMetersPerSecond?.let { speed ->
                        setAttribute(ExifInterface.TAG_GPS_SPEED_REF, "K")
                        setAttribute(
                            ExifInterface.TAG_GPS_SPEED,
                            rational(speed * METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR),
                        )
                    }
                    metadata.groundTrackDegrees?.let { track ->
                        setAttribute(ExifInterface.TAG_GPS_TRACK_REF, "T")
                        setAttribute(ExifInterface.TAG_GPS_TRACK, rational(track))
                    }
                }
                setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    JSONObject()
                        .put("source", "dji_video_downlink")
                        .put("time_semantics", "android_frame_receipt")
                        .put("gps_source", "aircraft_telemetry")
                        .put("gps_age_ms", metadata.gpsAgeMillis ?: JSONObject.NULL)
                        .put("gps_satellite_count", metadata.gpsSatelliteCount ?: JSONObject.NULL)
                        .put("gps_signal_level", metadata.gpsSignalLevel ?: JSONObject.NULL)
                        .put("frame_after_trigger_ms", metadata.frameAfterTriggerMillis)
                        .put("telemetry_after_frame_ms", metadata.telemetryAfterFrameMillis)
                        .put("heading_deg_true", metadata.headingDegrees ?: JSONObject.NULL)
                        .put("aircraft_heading_deg_true", metadata.headingDegrees ?: JSONObject.NULL)
                        .put("heading_age_ms", metadata.headingAgeMillis ?: JSONObject.NULL)
                        .put("roll_deg", metadata.rollDegrees ?: JSONObject.NULL)
                        .put("pitch_deg", metadata.pitchDegrees ?: JSONObject.NULL)
                        .put("yaw_deg", metadata.yawDegrees ?: JSONObject.NULL)
                        .put("attitude_age_ms", metadata.attitudeAgeMillis ?: JSONObject.NULL)
                        .put("gimbal_pitch_deg", metadata.gimbalPitchDegrees ?: JSONObject.NULL)
                        .put("gimbal_roll_deg", metadata.gimbalRollDegrees ?: JSONObject.NULL)
                        .put("gimbal_yaw_deg_ned", metadata.gimbalYawDegrees ?: JSONObject.NULL)
                        .put(
                            "gimbal_yaw_relative_to_aircraft_deg",
                            metadata.gimbalYawRelativeToAircraftHeadingDegrees ?: JSONObject.NULL,
                        )
                        .put("gimbal_attitude_age_ms", metadata.gimbalAttitudeAgeMillis ?: JSONObject.NULL)
                        .put(
                            "gimbal_yaw_relative_age_ms",
                            metadata.gimbalYawRelativeAgeMillis ?: JSONObject.NULL,
                        )
                        .put("camera_roll_deg", metadata.cameraRollDegrees ?: JSONObject.NULL)
                        .put("camera_pitch_deg", metadata.cameraPitchDegrees ?: JSONObject.NULL)
                        .put("camera_yaw_deg_true", metadata.cameraYawDegrees ?: JSONObject.NULL)
                        .put("camera_yaw_source", metadata.cameraYawSource ?: JSONObject.NULL)
                        .put(
                            "camera_yaw_consistency_error_deg",
                            metadata.cameraYawConsistencyErrorDegrees ?: JSONObject.NULL,
                        )
                        .put("velocity_north_mps", metadata.velocityNorthMetersPerSecond ?: JSONObject.NULL)
                        .put("velocity_east_mps", metadata.velocityEastMetersPerSecond ?: JSONObject.NULL)
                        .put("velocity_up_mps", metadata.velocityUpMetersPerSecond ?: JSONObject.NULL)
                        .put("ground_speed_mps", metadata.groundSpeedMetersPerSecond ?: JSONObject.NULL)
                        .put("ground_track_deg_true", metadata.groundTrackDegrees ?: JSONObject.NULL)
                        .put("velocity_age_ms", metadata.velocityAgeMillis ?: JSONObject.NULL)
                        .put("altitude_asl_m", metadata.altitudeAboveSeaLevelMeters ?: JSONObject.NULL)
                        .put("altitude_asl_source", metadata.altitudeAboveSeaLevelSource ?: JSONObject.NULL)
                        .put("relative_altitude_m", metadata.relativeAltitudeMeters ?: JSONObject.NULL)
                        .put("altitude_agl_m", metadata.altitudeAboveGroundMeters ?: JSONObject.NULL)
                        .toString(),
                )
                saveAttributes()
            }
            temporary.readBytes()
        } finally {
            temporary.delete()
        }
    }

    private fun localDateTime(date: Date): String = SimpleDateFormat(
        "yyyy:MM:dd HH:mm:ss",
        Locale.US,
    ).apply { timeZone = TimeZone.getDefault() }.format(date)

    private fun localOffset(date: Date): String {
        val totalMinutes = TimeZone.getDefault().getOffset(date.time) / 60_000
        val sign = if (totalMinutes >= 0) '+' else '-'
        val absolute = kotlin.math.abs(totalMinutes)
        return String.format(Locale.US, "%c%02d:%02d", sign, absolute / 60, absolute % 60)
    }

    private fun subsecond(epochMillis: Long): String =
        String.format(Locale.US, "%03d", Math.floorMod(epochMillis, 1_000L))

    private fun rational(value: Double): String =
        "${kotlin.math.round(value * HEADING_RATIONAL_SCALE).toLong()}/$HEADING_RATIONAL_SCALE"

    private companion object {
        const val HEADING_RATIONAL_SCALE = 1_000L
        const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6
    }
}
