package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyFrameMetadataPolicyTest {
    private val trigger = SurveyPhotoTrigger(
        missionId = "mission",
        backend = SurveyExecutionBackend.DJI_KMZ,
        passIndex = 1,
        waypointIndex = 2,
        captureView = "NADIR",
        reason = "distance",
        triggeredAtNanos = 10_000_000_000L,
        triggeredAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun usesFreshAircraftTelemetryAtFrameReceipt() {
        val metadata = SurveyFrameMetadataPolicy.resolve(
            trigger = trigger,
            frameCapturedAtNanos = 10_350_000_000L,
            telemetry = SurveyFrameTelemetry(
                sampledAtNanos = 10_360_000_000L,
                aircraftLocation = GeoPoint(31.025, 121.437),
                aircraftLocationUpdatedAtNanos = 10_300_000_000L,
                altitudeAboveSeaLevelMeters = 18.5,
                relativeAltitudeMeters = 7.2,
                altitudeAboveGroundMeters = 6.8,
                headingDegrees = -10.0,
                headingUpdatedAtNanos = 10_320_000_000L,
                rollDegrees = 1.5,
                pitchDegrees = -3.0,
                yawDegrees = -20.0,
                attitudeUpdatedAtNanos = 10_310_000_000L,
                velocityNorthMetersPerSecond = 3.0,
                velocityEastMetersPerSecond = 4.0,
                velocityUpMetersPerSecond = 0.5,
                velocityUpdatedAtNanos = 10_330_000_000L,
                gimbalPitchDegrees = -80.0,
                gpsSatelliteCount = 18,
                gpsSignalLevel = "LEVEL_5",
                gimbalRollDegrees = 0.5,
                gimbalYawDegrees = 15.0,
                gimbalYawRelativeToAircraftHeadingDegrees = 20.0,
                gimbalAttitudeUpdatedAtNanos = 10_340_000_000L,
                gimbalYawRelativeUpdatedAtNanos = 10_350_000_000L,
            ),
        )

        assertTrue(metadata.hasFreshAircraftGps)
        assertEquals(31.025, metadata.latitude!!, 0.0)
        assertEquals(121.437, metadata.longitude!!, 0.0)
        assertEquals(18.5, metadata.altitudeAboveSeaLevelMeters!!, 0.0)
        assertEquals(350.0, metadata.headingDegrees!!, 0.0)
        assertEquals(340.0, metadata.yawDegrees!!, 0.0)
        assertEquals(5.0, metadata.groundSpeedMetersPerSecond!!, 0.0)
        assertEquals(53.130102, metadata.groundTrackDegrees!!, 0.000001)
        assertEquals(60L, metadata.gpsAgeMillis)
        assertEquals(350L, metadata.frameAfterTriggerMillis)
        assertEquals(10L, metadata.telemetryAfterFrameMillis)
        assertEquals(1_700_000_000_350L, metadata.frameEpochMillis)
        assertEquals(15.0, metadata.cameraYawDegrees!!, 0.0)
        assertEquals(-80.0, metadata.cameraPitchDegrees!!, 0.0)
        assertEquals(0.5, metadata.cameraRollDegrees!!, 0.0)
        assertEquals(
            CameraOrientationResolver.SOURCE_ABSOLUTE_GIMBAL_CROSS_CHECKED,
            metadata.cameraYawSource,
        )
        assertEquals(5.0, metadata.cameraYawConsistencyErrorDegrees!!, 0.0)
        assertEquals(20L, metadata.gimbalAttitudeAgeMillis)
        assertEquals(10L, metadata.gimbalYawRelativeAgeMillis)
    }

    @Test
    fun rejectsStaleAircraftGpsInsteadOfWritingMisleadingExif() {
        val metadata = SurveyFrameMetadataPolicy.resolve(
            trigger = trigger,
            frameCapturedAtNanos = 10_350_000_000L,
            telemetry = SurveyFrameTelemetry(
                sampledAtNanos = 12_500_000_000L,
                aircraftLocation = GeoPoint(31.025, 121.437),
                aircraftLocationUpdatedAtNanos = 10_000_000_000L,
                altitudeAboveSeaLevelMeters = 18.5,
                relativeAltitudeMeters = 7.2,
                altitudeAboveGroundMeters = 6.8,
                headingDegrees = 90.0,
                headingUpdatedAtNanos = 10_000_000_000L,
                rollDegrees = 1.5,
                pitchDegrees = -3.0,
                yawDegrees = 90.0,
                attitudeUpdatedAtNanos = 10_000_000_000L,
                velocityNorthMetersPerSecond = 1.0,
                velocityEastMetersPerSecond = 0.0,
                velocityUpMetersPerSecond = 0.0,
                velocityUpdatedAtNanos = 10_000_000_000L,
                gimbalPitchDegrees = -80.0,
                gpsSatelliteCount = 18,
                gpsSignalLevel = "LEVEL_5",
            ),
        )

        assertFalse(metadata.hasFreshAircraftGps)
        assertEquals(null, metadata.altitudeAboveSeaLevelMeters)
        assertEquals(null, metadata.headingDegrees)
        assertEquals(2_500L, metadata.gpsAgeMillis)
        assertEquals(90.0, metadata.cameraYawDegrees!!, 0.0)
        assertEquals(CameraOrientationResolver.SOURCE_AIRCRAFT_HEADING_FALLBACK, metadata.cameraYawSource)
    }

    @Test
    fun keepsUnchangedPoseValuesWhenAircraftGpsIsFresh() {
        val metadata = SurveyFrameMetadataPolicy.resolve(
            trigger,
            10_350_000_000L,
            SurveyFrameTelemetry(
                sampledAtNanos = 10_360_000_000L,
                aircraftLocation = GeoPoint(31.025, 121.437),
                aircraftLocationUpdatedAtNanos = 10_300_000_000L,
                altitudeAboveSeaLevelMeters = 18.5,
                relativeAltitudeMeters = 7.2,
                altitudeAboveGroundMeters = 6.8,
                headingDegrees = 82.7,
                headingUpdatedAtNanos = 1_000_000_000L,
                rollDegrees = -0.9,
                pitchDegrees = 0.4,
                yawDegrees = 82.7,
                attitudeUpdatedAtNanos = 1_000_000_000L,
                velocityNorthMetersPerSecond = 0.2,
                velocityEastMetersPerSecond = 2.7,
                velocityUpMetersPerSecond = 0.0,
                velocityUpdatedAtNanos = 1_000_000_000L,
                gimbalPitchDegrees = -90.0,
                gpsSatelliteCount = 15,
                gpsSignalLevel = "LEVEL_5",
            ),
        )
        assertEquals(82.7, metadata.headingDegrees!!, 1e-9)
        assertEquals(-0.9, metadata.rollDegrees!!, 0.0)
        assertEquals(0.4, metadata.pitchDegrees!!, 0.0)
        assertEquals(82.7, metadata.yawDegrees!!, 1e-9)
        assertEquals(2.7, metadata.velocityEastMetersPerSecond!!, 0.0)
    }

    @Test
    fun rejectsSimulatorZeroAslWhenRelativeAltitudeIsNonzero() {
        val metadata = SurveyFrameMetadataPolicy.resolve(
            trigger,
            10_350_000_000L,
            SurveyFrameTelemetry(
                sampledAtNanos = 10_360_000_000L,
                aircraftLocation = GeoPoint(31.025, 121.437),
                aircraftLocationUpdatedAtNanos = 10_300_000_000L,
                altitudeAboveSeaLevelMeters = 0.0,
                relativeAltitudeMeters = 46.7,
                altitudeAboveGroundMeters = 2.5,
                headingDegrees = 0.0,
                headingUpdatedAtNanos = 10_300_000_000L,
                rollDegrees = 0.0, pitchDegrees = 0.0, yawDegrees = 0.0,
                attitudeUpdatedAtNanos = 10_300_000_000L,
                velocityNorthMetersPerSecond = 0.0,
                velocityEastMetersPerSecond = 0.0,
                velocityUpMetersPerSecond = 0.0,
                velocityUpdatedAtNanos = 10_300_000_000L,
                gimbalPitchDegrees = -90.0,
                gpsSatelliteCount = 15,
                gpsSignalLevel = "LEVEL_5",
            ),
        )
        assertEquals(null, metadata.altitudeAboveSeaLevelMeters)
        assertEquals(46.7, metadata.relativeAltitudeMeters!!, 0.0)
    }
}
