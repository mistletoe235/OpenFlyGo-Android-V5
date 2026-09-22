package edu.playground.djivln.adapter.dji

import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.SurveyCaptureTriggerMode
import edu.playground.djivln.survey.SurveyObliqueHeadingMode
import edu.playground.djivln.survey.SurveyRegressionMissionFactory
import edu.playground.djivln.survey.SurveyTerrainPlan
import edu.playground.djivln.survey.SurveyTerrainSourceKind
import edu.playground.djivln.survey.SurveyWaypointKind
import edu.playground.djivln.survey.surveyPasses
import dji.sdk.wpmz.value.mission.CameraLensType
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostBehavior
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineActionTriggerType
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingMode
import java.io.File
import dji.sdk.wpmz.value.mission.WaylineWaypointYawMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SurveyWpmzConverterTest {
    @Test
    fun `planned fixed nadir headings reach DJI waypoint yaw without alternating turns`() {
        for (collection in edu.playground.djivln.survey.SurveyCollectionMode.values()) {
            val source = edu.playground.djivln.survey.SurveyPlanner.plan(
                name = "nadir-fixed-kmz",
                roi = listOf(GeoPoint(31.0, 121.0), GeoPoint(31.0, 121.001),
                    GeoPoint(31.0006, 121.001), GeoPoint(31.0006, 121.0)),
                constraints = edu.playground.djivln.survey.SurveyConstraints(
                    altitudeMetersAgl = 30.0, routeHeadingDegrees = 350.0,
                    collectionMode = collection,
                    obliqueHeadingMode = SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION,
                ),
            )
            val converted = SurveyWpmzConverter.convert(source)
            val nadir = source.waypoints.zip(converted.wayline.waypoints)
                .filter { it.first.captureView == edu.playground.djivln.survey.SurveyCaptureView.NADIR }
            assertTrue(nadir.size >= 4)
            val expected = if (collection == edu.playground.djivln.survey.SurveyCollectionMode.CROSSHATCH_NADIR) {
                setOf(-10.0, 80.0)
            } else setOf(-10.0)
            assertEquals(expected, nadir.map { it.second.yawParam.yawAngle }.toSet())
            nadir.forEach { (_, waypoint) ->
                assertEquals(WaylineWaypointYawMode.FIXED, waypoint.yawParam.yawMode)
                assertEquals(true, waypoint.yawParam.enableYawAngle)
                assertEquals(dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE,
                    waypoint.turnParam.turnMode)
                assertEquals(0.0, waypoint.turnParam.turnDampingDistance, 0.0)
            }
            assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
        }
    }

    @Test
    fun `lost rc link keeps aircraft hovering instead of returning home`() {
        val converted = SurveyWpmzConverter.convert(
            SurveyRegressionMissionFactory.create(
                center = GeoPoint(31.025, 121.435),
                fiveDirection = true,
            ),
        )

        assertEquals(WaylineExitOnRCLostBehavior.EXCUTE_RC_LOST_ACTION, converted.config.exitOnRCLostBehavior)
        assertEquals(WaylineExitOnRCLostAction.HOVER, converted.config.exitOnRCLostType)
    }

    @Test
    fun `active recapture point uses reach point photo action`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(root, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v1.json"),
            File(root.parentFile, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v1.json"),
        ).first(File::isFile)
        val source = edu.playground.djivln.survey.SurveyMissionJson.decode(fixture.readText())

        val converted = SurveyWpmzConverter.convert(source)
        val pointPasses = source.surveyPasses().filter { it.isPointCapture }

        assertEquals(33, pointPasses.size)
        pointPasses.forEach { pass ->
            val group = converted.wayline.actionGroups.single { candidate ->
                candidate.startIndex == pass.firstWaypointIndex &&
                    candidate.endIndex == pass.lastWaypointIndex &&
                    candidate.actions.any { it.actionType == WaylineActionType.TAKE_PHOTO }
            }
            assertEquals(pass.firstWaypointIndex, group.startIndex)
            assertEquals(pass.lastWaypointIndex, group.endIndex)
            assertEquals(WaylineActionTriggerType.REACH_POINT, group.trigger.triggerType)
            val expectedActions = listOf(
                WaylineActionType.GIMBAL_ROTATE,
                WaylineActionType.HOVER,
                WaylineActionType.TAKE_PHOTO,
            )
            assertEquals(expectedActions, group.actions.map { it.actionType })
        }
        assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
    }

    @Test
    fun `V30 transit units never receive camera actions`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val directory = listOf(
            File(root, "testdata/active-recapture/two-buildings"),
            File(requireNotNull(root.parentFile), "testdata/active-recapture/two-buildings"),
        ).first { File(it, "openfly-active-recapture-two-buildings-v30-manifest.json").isFile }
        val manifest = org.json.JSONObject(
            File(directory, "openfly-active-recapture-two-buildings-v30-manifest.json").readText(),
        )
        val filename = manifest.getJSONArray("sorties").getJSONObject(0).getString("file")
        val source = edu.playground.djivln.survey.SurveyMissionJson.decode(
            File(directory, filename).readText(),
        )

        val converted = SurveyWpmzConverter.convert(source)
        val transitIndices = source.surveyPasses()
            .filter { it.isTransitOnly }
            .flatMap { it.firstWaypointIndex..it.lastWaypointIndex }
            .toSet()

        assertTrue(transitIndices.isNotEmpty())
        assertTrue(converted.wayline.actionGroups.none { group ->
            (group.startIndex..group.endIndex).any { it in transitIndices }
        })
        assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
    }

    @Test
    fun `V30 active recapture flies through eligible transit interpolation points`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(root, "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v30.json"),
            File(requireNotNull(root.parentFile), "testdata/active-recapture/two-buildings/openfly-active-recapture-two-buildings-v30.json"),
        ).first(File::isFile)
        val source = edu.playground.djivln.survey.SurveyMissionJson.decode(fixture.readText())

        var passThroughCount = 0
        DjiWaylinePartition.segments(source).forEach { segment ->
            val converted = SurveyWpmzConverter.convert(segment.mission, waylineId = segment.waylineId)
            segment.mission.waypoints.zip(converted.wayline.waypoints).forEachIndexed { index, (expected, actual) ->
                assertEquals(WaylineWaypointGimbalHeadingMode.SMOOTH_TRANSITION, actual.gimbalHeadingParam.headingMode)
                if (actual.turnParam.turnMode ==
                    dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE
                ) {
                    assertEquals(SurveyWaypointKind.TRANSIT, expected.kind)
                    assertTrue(index > 0 && index < segment.mission.waypoints.lastIndex)
                    assertTrue(actual.turnParam.turnDampingDistance >= 0.2)
                    passThroughCount += 1
                } else {
                    assertEquals(
                        "wayline ${segment.waylineId} waypoint $index",
                        dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE,
                        actual.turnParam.turnMode,
                    )
                    assertEquals(0.0, actual.turnParam.turnDampingDistance, 1e-6)
                }
            }
            assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
        }
        assertTrue("expected most interpolation points to remain continuous", passThroughCount > 500)
    }

    @Test
    fun `normalizes planner headings into DJI signed yaw range`() {
        assertEquals(-11.1, SurveyWpmzConverter.normalizeDjiWaypointYaw(348.9), 1e-6)
        assertEquals(-101.1, SurveyWpmzConverter.normalizeDjiWaypointYaw(258.9), 1e-6)
        assertEquals(168.9, SurveyWpmzConverter.normalizeDjiWaypointYaw(168.9), 1e-6)
        assertEquals(180.0, SurveyWpmzConverter.normalizeDjiWaypointYaw(180.0), 1e-6)
        assertEquals(-180.0, SurveyWpmzConverter.normalizeDjiWaypointYaw(-180.0), 1e-6)
    }

    @Test
    fun `converted waypoints never contain out of range DJI yaw`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        )

        val converted = SurveyWpmzConverter.convert(source)

        assertTrue(converted.wayline.waypoints.isNotEmpty())
        converted.wayline.waypoints.forEach { waypoint ->
            assertTrue(waypoint.yawParam.yawAngle in -180.0..180.0)
        }
    }

    @Test
    fun `converted mission satisfies strict DJI contract validation`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        )

        val errors = DjiWpmzContractValidator.validate(SurveyWpmzConverter.convert(source))

        assertTrue(errors.joinToString(), errors.isEmpty())
    }

    @Test
    fun `photo action groups contain the V5 action tree required by WPMZ writer`() {
        val converted = SurveyWpmzConverter.convert(
            SurveyRegressionMissionFactory.create(
                center = GeoPoint(31.025, 121.435),
                fiveDirection = true,
            ),
        )

        converted.wayline.actionGroups.forEach { group ->
            assertEquals(2, group.nodeLists.size)
            assertEquals(group.actions.size, group.nodeLists.first().nodes.single().childrenNum)
            assertEquals(group.actions.indices.toList(), group.nodeLists[1].nodes.map { it.actionIndex })
        }
    }

    @Test
    fun `maps main and enterprise camera positions to sparse WPMZ payload indices`() {
        assertEquals(0, DjiWpmzPayloadPosition.fromComponentValue(0))
        assertEquals(1, DjiWpmzPayloadPosition.fromComponentValue(1))
        assertEquals(2, DjiWpmzPayloadPosition.fromComponentValue(2))
        assertEquals(20_001, DjiWpmzPayloadPosition.fromComponentValue(20_001))
        assertEquals(20_002, DjiWpmzPayloadPosition.fromComponentValue(20_002))
        assertEquals(20_003, DjiWpmzPayloadPosition.fromComponentValue(20_003))
        assertEquals(20_007, DjiWpmzPayloadPosition.fromComponentValue(20_007))
        assertEquals(null, DjiWpmzPayloadPosition.fromComponentValue(65_535))
    }

    @Test
    fun `enterprise payload port is accepted by WPMZ converter`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )
        val converted = SurveyWpmzConverter.convert(source, payloadPositionIndex = 20_001)
        converted.wayline.actionGroups.flatMap { it.actions }.forEach { action ->
            val payloadPositionIndex = when (action.actionType) {
                WaylineActionType.GIMBAL_ROTATE -> action.gimbalRotateParam.payloadPositionIndex
                WaylineActionType.TAKE_PHOTO -> action.takePhotoParam.payloadPositionIndex
                WaylineActionType.HOVER -> return@forEach
                else -> error("unexpected action type: ${action.actionType}")
            }
            assertEquals(20_001, payloadPositionIndex)
        }
    }

    @Test fun `maps active stream source to WPMZ capture lens`() {
        assertEquals(CameraLensType.WIDE, DjiWpmzPayloadLens.fromStreamSourceName("WIDE_CAMERA"))
        assertEquals(CameraLensType.ZOOM, DjiWpmzPayloadLens.fromStreamSourceName("ZOOM_CAMERA"))
        assertEquals(CameraLensType.IR, DjiWpmzPayloadLens.fromStreamSourceName("INFRARED_CAMERA"))
        assertEquals(CameraLensType.VISABLE, DjiWpmzPayloadLens.fromStreamSourceName("RGB_CAMERA"))
        assertEquals(CameraLensType.NARROW_BAND, DjiWpmzPayloadLens.fromStreamSourceName("MS_NIR_CAMERA"))
        assertEquals(null, DjiWpmzPayloadLens.fromStreamSourceName("NDVI_CAMERA"))
        assertEquals(null, DjiWpmzPayloadLens.fromStreamSourceName(null))
    }

    @Test fun `writes selected lens into every photo action`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )

        val converted = SurveyWpmzConverter.convert(source, payloadLensType = CameraLensType.ZOOM)

        converted.wayline.actionGroups.flatMap { it.actions }
            .filter { it.actionType == WaylineActionType.TAKE_PHOTO }
            .forEach { action ->
            assertEquals(listOf(CameraLensType.ZOOM), action.takePhotoParam.payloadLensIndex)
        }
    }

    @Test fun `all waypoints use proven discontinuity stop mode`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )

        val converted = SurveyWpmzConverter.convert(source)

        converted.wayline.waypoints.forEach { actual ->
            assertEquals(
                dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE,
                actual.turnParam.turnMode,
            )
            assertEquals(0.0, actual.turnParam.turnDampingDistance, 1e-6)
        }
    }

    @Test fun `disabled terrain missions cannot be exported as DJI KMZ`() {
        val planned = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )
        val source = planned.copy(
            terrainPlan = SurveyTerrainPlan(
                sourceName = "test DSM",
                sourceSha256 = "abc",
                epsg = 4326,
                targetAglMeters = 30.0,
                takeoffTerrainElevationMeters = 5.0,
                sampleSpacingMeters = 1.0,
                minimumTerrainElevationMeters = 5.0,
                maximumTerrainElevationMeters = 12.0,
                minimumWaypointAltitudeMeters = 30.0,
                maximumWaypointAltitudeMeters = 37.0,
                sourceKind = SurveyTerrainSourceKind.SURFACE_DSM,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SurveyWpmzConverter.convert(source)
        }
    }

    @Test
    fun `default route following preserves geometry pitch and speed without fixed yaw`() {
        val base = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        )
        val source = base.copy(
            constraints = base.constraints.copy(
                speedMetersPerSecond = 2.0,
                obliqueSpeedMetersPerSecond = 5.0,
            ),
        )
        val converted = SurveyWpmzConverter.convert(source)

        assertEquals(source.waypoints.size, converted.wayline.waypoints.size)
        assertEquals(2.0, converted.wayline.autoFlightSpeed, 1e-6)
        source.waypoints.zip(converted.wayline.waypoints).forEach { (expected, actual) ->
            assertEquals(expected.point.latitude, actual.location.latitude, 1e-9)
            assertEquals(expected.point.longitude, actual.location.longitude, 1e-9)
            assertEquals(expected.point.altitudeMeters, actual.executeHeight, 1e-6)
            assertEquals(WaylineWaypointYawMode.FOLLOW_WAYLINE, actual.yawParam.yawMode)
            assertEquals(false, actual.yawParam.enableYawAngle)
            assertEquals(0.0, actual.yawParam.yawAngle, 1e-6)
            assertEquals(WaylineWaypointGimbalHeadingMode.FIXED, actual.gimbalHeadingParam.headingMode)
            assertEquals(expected.gimbalPitchDegrees, actual.gimbalHeadingParam.pitchAngle, 1e-6)
            assertEquals(source.constraints.speedForCaptureView(expected.captureView), actual.speed, 1e-6)
        }
        val passes = source.surveyPasses()
        val expectedGroupCount = passes.map { if (it.isPointCapture) 1 else 2 }.sum()
        assertEquals(expectedGroupCount, converted.wayline.actionGroups.size)
        val photoGroups = converted.wayline.actionGroups.filter { group ->
            group.actions.any { it.actionType == WaylineActionType.TAKE_PHOTO }
        }
        assertTrue(photoGroups.all { it.trigger.distanceInterval > 0.0 })
    }

    @Test
    fun `android triggered capture omits KMZ photo actions but keeps gimbal setup`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        )

        val converted = SurveyWpmzConverter.convert(source, includePhotoActions = false)

        assertTrue(converted.wayline.actionGroups.flatMap { it.actions }
            .none { it.actionType == WaylineActionType.TAKE_PHOTO })
        assertTrue(converted.wayline.actionGroups.flatMap { it.actions }
            .any { it.actionType == WaylineActionType.GIMBAL_ROTATE })
    }

    @Test
    fun `every capture pass explicitly rotates gimbal to its planned pitch`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        )
        val converted = SurveyWpmzConverter.convert(source)

        assertTrue(converted.wayline.waylineStartActions.isEmpty())

        source.surveyPasses().forEach { pass ->
            val gimbalAction = converted.wayline.actionGroups
                .filter { it.startIndex == pass.firstWaypointIndex }
                .flatMap { it.actions }
                .single { it.actionType == WaylineActionType.GIMBAL_ROTATE }
            with(gimbalAction.gimbalRotateParam) {
                assertEquals(WaylineGimbalActuatorRotateMode.ABSOLUTE_ANGLE, rotateMode)
                assertEquals(true, enablePitch)
                assertEquals(pass.start.gimbalPitchDegrees, pitch, 1e-6)
                assertEquals(true, enableRotateTime)
                assertEquals(2.0, rotateTime, 1e-6)
            }
        }
    }

    @Test
    fun `consecutive passes settle after heading changes but not for aligned headings`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )
        assertTrue(source.surveyPasses().size > 1)
        for ((headingChange, mustSettle) in listOf(0.0 to false, 3.0 to false, 3.1 to true, 180.0 to true)) {
            val controlled = source.copy(waypoints = source.waypoints.map { waypoint ->
                val heading = if (waypoint.passIndex % 2 == 0) 359.0 else (359.0 + headingChange) % 360.0
                waypoint.copy(headingDegrees = heading)
            })
            val converted = SurveyWpmzConverter.convert(controlled)
            controlled.surveyPasses().forEachIndexed { index, pass ->
                val setupActions = converted.wayline.actionGroups
                    .single { it.startIndex == pass.firstWaypointIndex && it.endIndex == pass.firstWaypointIndex }
                    .actions
                assertEquals("heading change=$headingChange pass=$index", index == 0 || mustSettle,
                    setupActions.any { it.actionType == WaylineActionType.HOVER })
            }
            assertTrue(DjiWpmzContractValidator.validate(converted).isEmpty())
        }
    }

    @Test
    fun `explicit fixed capture direction preserves signed fixed yaw`() {
        val planned = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = true,
        )
        val source = planned.copy(
            constraints = planned.constraints.copy(
                obliqueHeadingMode = SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION,
            ),
        )

        val converted = SurveyWpmzConverter.convert(source)

        source.waypoints.zip(converted.wayline.waypoints).forEach { (expected, actual) ->
            assertEquals(WaylineWaypointYawMode.FIXED, actual.yawParam.yawMode)
            assertEquals(true, actual.yawParam.enableYawAngle)
            assertEquals(
                SurveyWpmzConverter.normalizeDjiWaypointYaw(expected.headingDegrees),
                actual.yawParam.yawAngle,
                1e-6,
            )
        }
    }

    @Test
    fun `uses validated live rth height and discovered payload index`() {
        val source = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )

        val converted = SurveyWpmzConverter.convert(
            source = source,
            globalRthHeightMeters = 87.0,
            payloadPositionIndex = 3,
        )

        assertEquals(87.0, converted.config.globalRTHHeight, 1e-6)
        assertTrue(converted.config.isGlobalRTHHeightSet)
        converted.wayline.actionGroups.flatMap { it.actions }.forEach { action ->
            val payloadPositionIndex = when (action.actionType) {
                WaylineActionType.GIMBAL_ROTATE -> action.gimbalRotateParam.payloadPositionIndex
                WaylineActionType.TAKE_PHOTO -> action.takePhotoParam.payloadPositionIndex
                WaylineActionType.HOVER -> return@forEach
                else -> error("unexpected action type: ${action.actionType}")
            }
            assertEquals(3, payloadPositionIndex)
        }
    }

    @Test
    fun `time capture emits timing trigger instead of distance trigger`() {
        val planned = SurveyRegressionMissionFactory.create(
            center = GeoPoint(31.025, 121.435),
            fiveDirection = false,
        )
        val source = planned.copy(
            constraints = planned.constraints.copy(
                captureTriggerMode = SurveyCaptureTriggerMode.TIME,
                timedCaptureIntervalSeconds = 2.5,
            ),
        )

        val converted = SurveyWpmzConverter.convert(source)

        val photoGroups = converted.wayline.actionGroups.filter { group ->
            group.actions.any { it.actionType == WaylineActionType.TAKE_PHOTO }
        }
        assertTrue(photoGroups.isNotEmpty())
        photoGroups.forEach { group ->
            assertEquals(
                dji.sdk.wpmz.value.mission.WaylineActionTriggerType.MULTIPLE_TIMING,
                group.trigger.triggerType,
            )
            assertEquals(2.5, group.trigger.timeInterval, 1e-6)
            assertEquals(0.0, group.trigger.distanceInterval, 1e-6)
        }
    }
}
