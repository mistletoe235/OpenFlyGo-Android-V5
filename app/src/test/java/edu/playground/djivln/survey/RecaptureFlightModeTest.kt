package edu.playground.djivln.survey

import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode
import edu.playground.djivln.adapter.dji.DjiWaylinePartition
import edu.playground.djivln.adapter.dji.DjiWpmzContractValidator
import edu.playground.djivln.adapter.dji.DjiWpmzRoutePolicy
import edu.playground.djivln.adapter.dji.SurveyWpmzConverter
import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecaptureFlightModeTest {
    private val stop = WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE
    private val through = WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE

    private fun mission(continuous: Boolean = true, count: Int = 5): SurveyMission {
        val base = SurveyRegressionMissionFactory.create(GeoPoint(31.0, 121.0), fiveDirection = false)
        val waypoints = (0 until count).map { index ->
            base.waypoints.first().copy(
                point = GeoPoint(31.0 + index * 0.0001, 121.0, 40.0),
                headingDegrees = 0.0,
                gimbalPitchDegrees = -45.0,
                kind = SurveyWaypointKind.CAPTURE_POINT,
                captureAction = CaptureAction.CAPTURE_ON_REACH,
                captureIntervalMeters = null,
                passIndex = index,
                captureView = SurveyCaptureView.LOCAL_OBLIQUE,
            )
        }
        return base.copy(waypoints = waypoints, estimatedPhotoCount = count,
            activeMapping = ActiveMappingMetadata(selectionMethod = "test", groundTruthUsed = false,
                gsUsedForSelection = false, ordinaryGpsUsed = true, sourceCaptureCount = 0,
                surveyCaptureCount = count, bridgeCaptureCount = 0, sourceEstimatedRouteDistanceMeters = 0.0),
            recaptureFlightMode = if (continuous) RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL else RecaptureFlightMode.STOP_AND_CAPTURE)
    }

    @Test fun legacyMissionsRemainSchema13AndStopByDefault() {
        val original = mission(false)
        val encoded = JSONObject(SurveyMissionJson.encode(original))
        assertEquals(13, encoded.getInt("schema_version"))
        assertFalse(encoded.has("recapture_flight_mode"))
        assertEquals(original, SurveyMissionJson.decode(encoded.toString()))
        val converted = SurveyWpmzConverter.convert(original)
        assertTrue(converted.wayline.waypoints.all { it.turnParam.turnMode == stop })
        assertEquals(5, converted.wayline.actionGroups.sumOf { group ->
            group.actions.count { it.actionType == WaylineActionType.HOVER }
        })
    }

    @Test fun continuousModeRoundTripsWithExplicitNewSchema() {
        val original = mission()
        val encoded = JSONObject(SurveyMissionJson.encode(original))
        assertEquals(14, encoded.getInt("schema_version"))
        assertEquals(original, SurveyMissionJson.decode(encoded.toString()))
        encoded.put("recapture_flight_mode", "UNKNOWN")
        assertTrue(runCatching { SurveyMissionJson.decode(encoded.toString()) }.isFailure)
        encoded.put("recapture_flight_mode", "CONTINUOUS_EXPERIMENTAL").put("schema_version", 13)
        assertTrue(runCatching { SurveyMissionJson.decode(encoded.toString()) }.isFailure)
    }

    @Test fun ordinaryMissionsCannotOptIntoRecaptureMode() {
        assertTrue(runCatching { mission().copy(activeMapping = null) }.isFailure)
    }

    @Test fun continuousExecutionSupportsRealAndSimulatedDjiKmzButRejectsOtherBackends() {
        assertTrue(RecaptureFlightModePolicy.canExecute(mission(), true))
        assertFalse(RecaptureFlightModePolicy.canExecute(mission(), false))
        assertTrue(RecaptureFlightModePolicy.canExecute(mission(false), true))
        assertTrue(RecaptureFlightModePolicy.canExecute(mission(false), false))
    }

    @Test fun continuousInteriorPointsHaveNoBlockingHoverOrRotateActions() {
        val source = mission()
        val converted = SurveyWpmzConverter.convert(source)
        assertEquals(setOf(1, 2, 3), converted.continuousCaptureWaypointIndices)
        assertEquals(converted.continuousCaptureWaypointIndices, DjiWpmzRoutePolicy.continuousCaptureIndices(source))
        assertEquals(listOf(stop, through, through, through, stop), converted.wayline.waypoints.map { it.turnParam.turnMode })
        converted.wayline.actionGroups.filter { it.startIndex in 1..3 }.forEach { group ->
            assertEquals(listOf(WaylineActionType.TAKE_PHOTO), group.actions.map { it.actionType })
        }
        assertTrue(DjiWpmzContractValidator.validate(converted).toString(), DjiWpmzContractValidator.validate(converted).isEmpty())
        val appCapture = SurveyWpmzConverter.convert(source, includePhotoActions = false)
        assertTrue(appCapture.wayline.actionGroups.none { it.startIndex in 1..3 })
        assertTrue(DjiWpmzContractValidator.validate(appCapture).isEmpty())
    }

    @Test fun continuousExceptionDoesNotWeakenDefaultGimbalValidation() {
        val converted = SurveyWpmzConverter.convert(mission(false))
        converted.wayline.actionGroups.first().actions = converted.wayline.actionGroups.first().actions
            .filter { it.actionType != WaylineActionType.GIMBAL_ROTATE }
        assertTrue(DjiWpmzContractValidator.validate(converted).any { it.contains("explicit gimbal") })
    }

    @Test fun sharpTurnsAltitudeAndPoseChangesStillStop() {
        val source = mission()
        val target = source.waypoints[2]
        val unsafe = listOf(
            target.copy(point = GeoPoint(target.point.latitude, target.point.longitude + 0.0002, 40.0)),
            target.copy(point = target.point.copy(altitudeMeters = 42.0)),
            target.copy(headingDegrees = 45.0),
            target.copy(gimbalPitchDegrees = -55.0),
            target.copy(point = source.waypoints[1].point),
        )
        unsafe.forEach { waypoint ->
            val waypoints = source.waypoints.toMutableList().also { it[2] = waypoint }
            assertEquals(stop, DjiWpmzRoutePolicy.turn(waypoints, 2, true, true).mode)
        }
    }

    @Test fun internalPartitionEndpointsRemainStops() {
        val source = mission(count = 202)
        val continuous = DjiWpmzRoutePolicy.continuousCaptureIndices(source)
        DjiWaylinePartition.segments(source).forEach { segment ->
            assertFalse(segment.firstGlobalWaypointIndex in continuous)
            assertFalse(segment.lastGlobalWaypointIndex in continuous)
            assertTrue(SurveyWpmzConverter.convert(segment.mission).continuousCaptureWaypointIndices.all {
                segment.globalWaypointIndex(it) in continuous
            })
        }
    }

    @Test fun baseline200MissionsPreserveCapturePointsAndUseConservativeEligibility() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        listOf("onthefly" to 128, "swiftmap" to 97).forEach { (name, expectedContinuous) ->
            val relative = "handoff/active_recapture/two_buildings/baselines/$name/openfly-survey-mission-schema13.json"
            val file = listOf(File(root, relative), File(root.parentFile, relative)).first(File::isFile)
            val original = SurveyMissionJson.decode(file.readText())
            val changed = original.copy(recaptureFlightMode = RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL)
            assertEquals(original.waypoints, changed.waypoints)
            assertEquals(200, changed.waypoints.count { it.captureAction == CaptureAction.CAPTURE_ON_REACH })
            assertEquals(expectedContinuous, DjiWpmzRoutePolicy.continuousCaptureIndices(changed).size)
            val segments = DjiWaylinePartition.segments(changed)
            assertEquals(expectedContinuous, segments.sumOf { segment ->
                val converted = SurveyWpmzConverter.convert(segment.mission)
                val errors = DjiWpmzContractValidator.validate(converted)
                assertTrue(errors.toString(), errors.isEmpty())
                converted.continuousCaptureWaypointIndices.size
            })
        }
    }

    @Test fun missedPointsAreReportedOnceAndNeverAsSuccess() {
        val source = mission()
        val coordinator = DjiKmzAppCaptureCoordinator()
        coordinator.arm(source, setOf(1, 2, 3))
        assertNull(coordinator.tick(source.waypoints[0].point, 0, 1000, false, 4.0))
        coordinator.currentGimbalTarget(1)
        assertEquals(listOf(0), coordinator.drainMissedPointPasses())
        coordinator.currentGimbalTarget(1)
        assertTrue(coordinator.drainMissedPointPasses().isEmpty())
        val request = requireNotNull(coordinator.tick(source.waypoints[1].point, 1, 2000, true, 4.0))
        coordinator.onCaptureResult(request.position, 2200, false, request)
        assertEquals(listOf(1), coordinator.drainMissedPointPasses())
    }

    @Test fun delayedCaptureCallbackDoesNotSkipTheNextPoint() {
        val source = mission()
        val coordinator = DjiKmzAppCaptureCoordinator()
        coordinator.arm(source, setOf(1, 2, 3))
        val first = requireNotNull(coordinator.tick(source.waypoints[0].point, 0, 1000, true, 4.0))
        coordinator.currentGimbalTarget(1)
        coordinator.onCaptureResult(first.position, 1500, true, first)
        val next = requireNotNull(coordinator.tick(source.waypoints[1].point, 1, 2000, true, 4.0))
        assertEquals(1, next.waypointIndex)
        assertTrue(coordinator.drainMissedPointPasses().isEmpty())
    }

    @Test fun staleCallbackFromPreviousSessionCannotCompleteNewRequest() {
        val source = mission()
        val coordinator = DjiKmzAppCaptureCoordinator()
        coordinator.arm(source)
        val old = requireNotNull(coordinator.tick(source.waypoints[0].point, 0, 1000, true, 0.0))
        coordinator.arm(source)
        val current = requireNotNull(coordinator.tick(source.waypoints[0].point, 0, 2000, true, 0.0))
        coordinator.onCaptureResult(old.position, 2100, false, old)
        assertTrue(coordinator.drainMissedPointPasses().isEmpty())
        coordinator.onCaptureResult(current.position, 2200, true, current)
        assertEquals(1, coordinator.progress(1)?.passIndex)
    }

    @Test fun recoveryDoesNotLabelPreviouslyFlownPointsAsMissed() {
        val source = mission()
        val coordinator = DjiKmzAppCaptureCoordinator()
        coordinator.armFromBreakpoint(source, WaylineBreakpoint(waylineId = 0, waypointId = 3, segmentProgress = 0.0), setOf(1, 2, 3))
        coordinator.currentGimbalTarget(3)
        assertTrue(coordinator.drainMissedPointPasses().isEmpty())
        coordinator.finish()
        assertEquals(listOf(3, 4), coordinator.drainMissedPointPasses())
    }

    @Test fun movingCaptureRequiresTighterPositionAndAltitude() {
        val source = mission()
        val coordinator = DjiKmzAppCaptureCoordinator()
        coordinator.arm(source, setOf(1, 2, 3))
        val target = source.waypoints[1].point
        assertNull(coordinator.tick(target.copy(altitudeMeters = 45.0), 1, 1000, true, 4.0))
        assertNull(coordinator.tick(target.copy(latitude = target.latitude + 0.00003), 1, 1500, true, 4.0))
        assertNotNull(coordinator.tick(target, 1, 2000, true, 4.0))
    }

    @Test fun movingCaptureRejectsStaleOrMisalignedPose() {
        val target = mission().waypoints[1]
        val now = 2_000_000_000L
        val aircraft = AircraftSnapshot(connected = true, headingDegrees = 0.0, gimbalPitchDegrees = -45.0,
            relativeAltitudeMeters = 40.0, aircraftLocationUpdatedAtNanos = now,
            relativeAltitudeUpdatedAtNanos = now, headingUpdatedAtNanos = now, gimbalAttitudeUpdatedAtNanos = now)
        assertTrue(ContinuousCapturePosePolicy.ready(aircraft, target, now))
        assertFalse(ContinuousCapturePosePolicy.ready(aircraft, target, now + 1_000_000_001))
        assertFalse(ContinuousCapturePosePolicy.ready(aircraft.copy(headingDegrees = 10.0), target, now))
        assertFalse(ContinuousCapturePosePolicy.ready(aircraft.copy(gimbalPitchDegrees = -55.0), target, now))
        assertFalse(ContinuousCapturePosePolicy.ready(aircraft.copy(relativeAltitudeMeters = 45.0), target, now))
        assertFalse(ContinuousCapturePosePolicy.ready(aircraft.copy(headingUpdatedAtNanos = 0), target, now))
    }

    @Test fun stoppedCaptureRequiresFreshAlignedStationaryPoseForContinuousDwell() {
        val target = mission(false).waypoints[1]
        val now = 2_000_000_000L
        val position = target.point
        val aircraft = AircraftSnapshot(
            connected = true,
            aircraftLocation = edu.playground.djivln.domain.telemetry.GeoPoint(
                position.latitude,
                position.longitude,
                position.altitudeMeters,
            ),
            aircraftLocationUpdatedAtNanos = now,
            relativeAltitudeMeters = position.altitudeMeters,
            relativeAltitudeUpdatedAtNanos = now,
            velocity = edu.playground.djivln.domain.telemetry.VelocityMetersPerSecond(0.0, 0.0, 0.0),
            velocityUpdatedAtNanos = now,
            headingDegrees = target.headingDegrees,
            headingUpdatedAtNanos = now,
            gimbalPitchDegrees = target.gimbalPitchDegrees,
            gimbalAttitudeUpdatedAtNanos = now,
        )
        assertTrue(StoppedCapturePosePolicy.aligned(aircraft, target, position, 0.0, now))
        assertFalse(StoppedCapturePosePolicy.aligned(
            aircraft.copy(headingDegrees = target.headingDegrees + 8.0), target, position, 0.0, now,
        ))
        assertFalse(StoppedCapturePosePolicy.aligned(aircraft, target, position, 0.8, now))
        assertFalse(StoppedCapturePosePolicy.aligned(
            aircraft, target, position.copy(latitude = position.latitude + 0.00003), 0.0, now,
        ))
        val stableSince = StoppedCapturePosePolicy.updatedStableSince(true, 0L, 1_000L)
        assertFalse(StoppedCapturePosePolicy.stable(stableSince, 1_799L))
        assertTrue(StoppedCapturePosePolicy.stable(stableSince, 1_800L))
        assertEquals(0L, StoppedCapturePosePolicy.updatedStableSince(false, stableSince, 1_500L))
    }
}
