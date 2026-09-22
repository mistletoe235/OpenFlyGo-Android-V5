package edu.playground.djivln.adapter.dji

import android.content.Context
import edu.playground.djivln.R
import edu.playground.djivln.survey.SurveyFeatureAvailability
import com.dji.wpmzsdk.manager.WPMZManager
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam
import dji.sdk.wpmz.value.mission.ActionAircraftHoverParam
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam
import dji.sdk.wpmz.value.mission.CameraLensType
import dji.sdk.wpmz.value.mission.GimbalHeadingYawBase
import dji.sdk.wpmz.value.mission.Wayline
import dji.sdk.wpmz.value.mission.WaylineActionGroup
import dji.sdk.wpmz.value.mission.WaylineActionInfo
import dji.sdk.wpmz.value.mission.WaylineActionNodeList
import dji.sdk.wpmz.value.mission.WaylineActionTrigger
import dji.sdk.wpmz.value.mission.WaylineActionTriggerType
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineActionTreeNode
import dji.sdk.wpmz.value.mission.WaylineActionsRelationType
import dji.sdk.wpmz.value.mission.WaylineExecuteAltitudeMode
import dji.sdk.wpmz.value.mission.WaylineExecuteCoordinateMode
import dji.sdk.wpmz.value.mission.WaylineExecuteWaypoint
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostBehavior
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.wpmz.value.mission.WaylineFlyToWaylineMode
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D
import dji.sdk.wpmz.value.mission.WaylineMission
import dji.sdk.wpmz.value.mission.WaylineMissionConfig
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingMode
import dji.sdk.wpmz.value.mission.WaylineWaypointGimbalHeadingParam
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnParam
import dji.sdk.wpmz.value.mission.WaylineWaypointYawMode
import dji.sdk.wpmz.value.mission.WaylineWaypointYawParam
import dji.sdk.wpmz.value.mission.WaylineWaypointYawPathMode
import edu.playground.djivln.survey.SurveyCaptureTriggerMode
import edu.playground.djivln.survey.SurveyCompletionAction
import edu.playground.djivln.survey.DjiCameraProfileCatalog
import edu.playground.djivln.survey.DjiWaylineCaptureStrategy
import edu.playground.djivln.survey.GeoPoint
import edu.playground.djivln.survey.SurveyMission
import edu.playground.djivln.survey.SurveyObliqueHeadingMode
import edu.playground.djivln.survey.SurveyWaypoint
import edu.playground.djivln.survey.SurveyWaypointKind
import edu.playground.djivln.survey.surveyPasses
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class DjiWpmzMission(
    val mission: WaylineMission,
    val config: WaylineMissionConfig,
    val wayline: Wayline,
    val continuousCaptureWaypointIndices: Set<Int> = emptySet(),
)

data class WpmzValidationResult(
    val valid: Boolean,
    val details: String,
)

internal object DjiWpmzContractValidator {
    fun validate(model: DjiWpmzMission): List<String> = buildList {
        val config = model.config
        requireFinitePositive(config.globalTransitionalSpeed, "globalTransitionalSpeed")
        requireFinitePositive(config.securityTakeOffHeight, "securityTakeOffHeight")
        requireFinitePositive(config.globalRTHHeight, "globalRTHHeight")

        val waypoints = model.wayline.waypoints.orEmpty()
        if (waypoints.isEmpty()) add("wayline must contain at least one waypoint")
        val indices = waypoints.mapNotNull { it.waypointIndex }
        if (indices != waypoints.indices.toList()) add("waypoint indices must be contiguous from zero")
        waypoints.forEachIndexed { index, waypoint ->
            val latitude = waypoint.location?.latitude
            val longitude = waypoint.location?.longitude
            if (latitude == null || !latitude.isFinite() || latitude !in -90.0..90.0) {
                add("waypoint[$index] latitude is invalid: $latitude")
            }
            if (longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0) {
                add("waypoint[$index] longitude is invalid: $longitude")
            }
            requireFinitePositive(waypoint.executeHeight, "waypoint[$index].executeHeight")
            requireFinitePositive(waypoint.speed, "waypoint[$index].speed")
            val yaw = waypoint.yawParam?.yawAngle
            if (yaw == null || !yaw.isFinite() || yaw !in -180.0..180.0) {
                add("waypoint[$index] yaw must be within [-180, 180]: $yaw")
            }
            val pitch = waypoint.gimbalHeadingParam?.pitchAngle
            if (pitch == null || !pitch.isFinite() || pitch !in -90.0..30.0) {
                add("waypoint[$index] gimbal pitch must be within [-90, 30]: $pitch")
            }
            val damping = waypoint.turnParam?.turnDampingDistance
            if (damping == null || !damping.isFinite() || damping < 0.0) {
                add("waypoint[$index] turn damping is invalid: $damping")
            }
        }
        waypoints.zipWithNext().forEachIndexed { index, (start, end) ->
            val distance = DjiWpmzRoutePolicy.distanceMeters(
                GeoPoint(start.location.latitude, start.location.longitude),
                GeoPoint(end.location.latitude, end.location.longitude),
            )
            val dampingSum = start.turnParam.turnDampingDistance + end.turnParam.turnDampingDistance
            if (distance > 0.0 && dampingSum >= distance) {
                add("waypoint[$index..${index + 1}] damping sum $dampingSum exceeds leg distance $distance")
            }
            if (distance == 0.0 && dampingSum > 0.0) {
                add("waypoint[$index..${index + 1}] duplicate coordinate cannot use turn damping")
            }
        }

        val waypointLastIndex = waypoints.lastIndex
        val groupIds = HashSet<Int>()
        val actionIds = HashSet<Int>()
        val gimbalPitchByStartIndex = mutableMapOf<Int, MutableList<Double>>()
        val photoGroups = mutableListOf<WaylineActionGroup>()
        model.wayline.waylineStartActions.orEmpty().forEach { action ->
            val actionId = action.actionId
            if (actionId == null || !actionIds.add(actionId)) add("start actionId is missing or duplicated: $actionId")
            if (action.actionType != WaylineActionType.GIMBAL_ROTATE) {
                add("unsupported wayline start action: ${action.actionType}")
                return@forEach
            }
            val parameter = action.gimbalRotateParam
            val payloadIndex = parameter?.payloadPositionIndex
            if (payloadIndex !in SurveyWpmzConverter.supportedPayloadPositionIndices) {
                add("unsupported start gimbal payload position index: $payloadIndex")
            }
            if (parameter?.rotateMode != WaylineGimbalActuatorRotateMode.ABSOLUTE_ANGLE) {
                add("start gimbal action must use absolute angle mode")
            }
            if (parameter?.enablePitch != true) add("start gimbal action must enable pitch")
            val pitch = parameter?.pitch
            if (pitch == null || !pitch.isFinite() || pitch !in -90.0..30.0) {
                add("start gimbal action pitch must be within [-90, 30]: $pitch")
            } else {
                gimbalPitchByStartIndex.getOrPut(0) { mutableListOf() } += pitch
            }
        }
        model.wayline.actionGroups.orEmpty().forEachIndexed { index, group ->
            val groupId = group.groupId
            if (groupId == null || !groupIds.add(groupId)) add("actionGroup[$index] groupId is missing or duplicated")
            val start = group.startIndex
            val end = group.endIndex
            if (start == null || end == null || start !in 0..waypointLastIndex || end !in start..waypointLastIndex) {
                add("actionGroup[$index] waypoint range is invalid: $start..$end")
            }
            val trigger = group.trigger
            when (trigger?.triggerType) {
                WaylineActionTriggerType.MULTIPLE_DISTANCE ->
                    requireFinitePositive(trigger.distanceInterval, "actionGroup[$index].distanceInterval")
                WaylineActionTriggerType.MULTIPLE_TIMING ->
                    requireFinitePositive(trigger.timeInterval, "actionGroup[$index].timeInterval")
                else -> Unit
            }
            group.actions.orEmpty().forEach { action ->
                val actionId = action.actionId
                if (actionId == null || !actionIds.add(actionId)) add("actionId is missing or duplicated: $actionId")
                when (action.actionType) {
                    WaylineActionType.TAKE_PHOTO -> {
                        photoGroups += group
                        val payloadIndex = action.takePhotoParam?.payloadPositionIndex
                        if (payloadIndex !in SurveyWpmzConverter.supportedPayloadPositionIndices) {
                            add("unsupported photo payload position index: $payloadIndex")
                        }
                    }
                    WaylineActionType.GIMBAL_ROTATE -> {
                        val parameter = action.gimbalRotateParam
                        val payloadIndex = parameter?.payloadPositionIndex
                        if (payloadIndex !in SurveyWpmzConverter.supportedPayloadPositionIndices) {
                            add("unsupported gimbal payload position index: $payloadIndex")
                        }
                        if (parameter?.rotateMode != WaylineGimbalActuatorRotateMode.ABSOLUTE_ANGLE) {
                            add("gimbal action must use absolute angle mode")
                        }
                        if (parameter?.enablePitch != true) add("gimbal action must enable pitch")
                        val pitch = parameter?.pitch
                        if (pitch == null || !pitch.isFinite() || pitch !in -90.0..30.0) {
                            add("gimbal action pitch must be within [-90, 30]: $pitch")
                        } else if (start != null) {
                            gimbalPitchByStartIndex.getOrPut(start) { mutableListOf() } += pitch
                        }
                    }
                    else -> Unit
                }
            }
            val nodeLists = group.nodeLists.orEmpty()
            if (nodeLists.size < 2 || nodeLists.firstOrNull()?.nodes.orEmpty().none {
                    it.nodeType == WaylineActionsRelationType.SEQUENCE && it.childrenNum == group.actions.orEmpty().size
                }
            ) {
                add("actionGroup[$index] is missing the V5 action tree")
            }
        }
        photoGroups.distinct().forEachIndexed { index, group ->
            val start = group.startIndex ?: return@forEachIndexed
            val expectedPitch = waypoints.getOrNull(start)?.gimbalHeadingParam?.pitchAngle
            val explicitPitches = gimbalPitchByStartIndex[start].orEmpty()
            if (start in model.continuousCaptureWaypointIndices &&
                waypoints[start].turnParam.turnMode == WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE &&
                waypoints[start].gimbalHeadingParam.headingMode == WaylineWaypointGimbalHeadingMode.SMOOTH_TRANSITION &&
                expectedPitch != null && expectedPitch.isFinite() && expectedPitch in -90.0..30.0
            ) return@forEachIndexed
            if (expectedPitch == null || explicitPitches.none { kotlin.math.abs(it - expectedPitch) < 1e-6 }) {
                add("photoGroup[$index] has no matching explicit gimbal pitch at waypoint $start: $expectedPitch")
            }
        }
    }

    fun validateArchive(file: File, requirePhotoActions: Boolean): List<String> = runCatching {
        ZipFile(file).use { zip ->
            buildList {
                addAll(REQUIRED_ENTRIES.filter { zip.getEntry(it) == null }.map { "KMZ missing required entry: $it" })
                if (requirePhotoActions) {
                    val wpml = zip.getEntry(WAYLINES_ENTRY)?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }.orEmpty()
                    if (!wpml.contains("<wpml:actionTriggerType>")) add("KMZ photo actions have no trigger definition")
                    if (!wpml.contains("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>")) {
                        add("KMZ does not contain a takePhoto actuator")
                    }
                    if (!wpml.contains("<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>")) {
                        add("KMZ does not contain an explicit gimbalRotate actuator")
                    }
                    if (!wpml.contains("<wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>")) {
                        add("KMZ gimbalRotate actuator does not enable pitch")
                    }
                }
            }
        }
    }.getOrElse { listOf("KMZ cannot be opened: ${it.message}") }

    private fun MutableList<String>.requireFinitePositive(value: Double?, field: String) {
        if (value == null || !value.isFinite() || value <= 0.0) add("$field must be finite and positive: $value")
    }

    private const val WAYLINES_ENTRY = "wpmz/waylines.wpml"
    private val REQUIRED_ENTRIES = listOf("wpmz/template.kml", WAYLINES_ENTRY)
}

object SurveyWpmzConverter {
    internal val supportedPayloadPositionIndices = setOf(
        0, 1, 2, 3, 6,
        20_001, 20_002, 20_003, 20_004, 20_005, 20_006, 20_007,
    )

    fun convert(
        source: SurveyMission,
        globalRthHeightMeters: Double = source.waypoints.maxOfOrNull { it.point.altitudeMeters }
            ?: source.constraints.safeTakeoffAltitudeMeters,
        payloadPositionIndex: Int = 0,
        payloadLensType: CameraLensType = CameraLensType.WIDE,
        includePhotoActions: Boolean = true,
        waylineId: Int = 0,
    ): DjiWpmzMission {
        SurveyFeatureAvailability.requireSupportedMission(source)
        val maximumMissionAltitude = source.waypoints.maxOfOrNull { it.point.altitudeMeters }
            ?: source.constraints.safeTakeoffAltitudeMeters
        require(globalRthHeightMeters + 0.5 >= maximumMissionAltitude) {
            "global RTH height must not be below the highest mission waypoint"
        }
        require(payloadPositionIndex in supportedPayloadPositionIndices) {
            "unsupported payload position index: $payloadPositionIndex"
        }
        val timestampSeconds = source.createdAtEpochMillis / 1_000.0
        val passes = source.surveyPasses()
        val mission = WaylineMission().apply {
            createTime = timestampSeconds
            updateTime = timestampSeconds
            author = "DJI-VLN Android V5"
        }
        val config = WaylineMissionConfig().apply {
            flyToWaylineMode = WaylineFlyToWaylineMode.SAFELY
            finishAction = when (source.constraints.completionAction) {
                SurveyCompletionAction.RETURN_TO_HOME -> WaylineFinishedAction.GO_HOME
                SurveyCompletionAction.HOVER -> WaylineFinishedAction.NO_ACTION
                SurveyCompletionAction.RETURN_TO_ROUTE_START -> WaylineFinishedAction.GOTO_FIRST_WAYPOINT
            }
            exitOnRCLostBehavior = WaylineExitOnRCLostBehavior.EXCUTE_RC_LOST_ACTION
            exitOnRCLostType = WaylineExitOnRCLostAction.HOVER
            globalTransitionalSpeed = source.constraints.takeoffSpeedMetersPerSecond
            securityTakeOffHeight = source.constraints.safeTakeoffAltitudeMeters
            isSecurityTakeOffHeightSet = true
            globalRTHHeight = globalRthHeightMeters
            isGlobalRTHHeightSet = true
        }
        val wayline = Wayline().apply {
            this.waylineId = waylineId
            templateId = 0
            distance = source.estimatedPathMeters
            duration = source.estimatedFlightSeconds
            autoFlightSpeed = source.constraints.speedMetersPerSecond
            mode = WaylineExecuteAltitudeMode.RELATIVE_TO_START_POINT
            coordinateMode = WaylineExecuteCoordinateMode.WGS84
            val smoothActiveRecapture = source.activeMapping != null
            waypoints = source.waypoints.mapIndexed { index, waypoint ->
                WaylineExecuteWaypoint().apply {
                    waypointIndex = index
                    location = WaylineLocationCoordinate2D(waypoint.point.latitude, waypoint.point.longitude)
                    executeHeight = waypoint.point.altitudeMeters
                    yawParam = WaylineWaypointYawParam().apply {
                        val fixedCaptureDirection = source.constraints.obliqueHeadingMode ==
                            SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION
                        yawMode = if (fixedCaptureDirection) {
                            WaylineWaypointYawMode.FIXED
                        } else {
                            WaylineWaypointYawMode.FOLLOW_WAYLINE
                        }
                        enableYawAngle = fixedCaptureDirection
                        yawAngle = if (fixedCaptureDirection) {
                            normalizeDjiWaypointYaw(waypoint.headingDegrees)
                        } else {
                            0.0
                        }
                        yawPathMode = WaylineWaypointYawPathMode.FOLLOW_BAD_ARC
                    }
                    gimbalHeadingParam = WaylineWaypointGimbalHeadingParam().apply {
                        headingMode = if (smoothActiveRecapture) {
                            WaylineWaypointGimbalHeadingMode.SMOOTH_TRANSITION
                        } else {
                            WaylineWaypointGimbalHeadingMode.FIXED
                        }
                        yawAngle = 0.0
                        pitchAngle = waypoint.gimbalPitchDegrees
                    }
                    val turn = DjiWpmzRoutePolicy.turn(
                        waypoints = source.waypoints,
                        index = index,
                        allowTransitPassThrough = smoothActiveRecapture,
                        allowCapturePassThrough = source.recaptureFlightMode ==
                            edu.playground.djivln.survey.RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL,
                    )
                    turnParam = WaylineWaypointTurnParam(turn.mode, turn.dampingMeters)
                    speed = source.constraints.speedForCaptureView(waypoint.captureView)
                    useStraightLine = true
                    isRisky = false
                    waypointWorkType = 0
                }
            }
            var nextGroupId = 0
            var nextActionId = 0
            waylineStartActions = emptyList()
            actionGroups = passes.flatMapIndexed { passListIndex, pass ->
                if (pass.isTransitOnly) return@flatMapIndexed emptyList()
                val continuousCapture = pass.isPointCapture && waypoints[pass.firstWaypointIndex].turnParam.turnMode ==
                    WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE
                if (continuousCapture) {
                    if (!includePhotoActions) return@flatMapIndexed emptyList()
                    return@flatMapIndexed listOf(createActionGroup(
                        groupId = nextGroupId++,
                        startIndex = pass.firstWaypointIndex,
                        endIndex = pass.lastWaypointIndex,
                        trigger = WaylineActionTrigger(WaylineActionTriggerType.REACH_POINT, 0.0, 0.0),
                        actions = listOf(createPhotoAction(
                            nextActionId++, payloadPositionIndex, payloadLensType,
                            "P${pass.start.passIndex}_${pass.start.captureView.name}",
                        )),
                    ))
                }
                val previousPass = passes.getOrNull(passListIndex - 1)
                val requiresSettle = pass.isPointCapture || previousPass == null ||
                    previousPass.isTransitOnly ||
                    previousPass.start.captureView != pass.start.captureView ||
                    previousPass.start.gimbalPitchDegrees != pass.start.gimbalPitchDegrees ||
                    headingDifference(
                        previousPass.end.headingDegrees,
                        pass.start.headingDegrees,
                    ) > CAPTURE_HEADING_SETTLE_THRESHOLD_DEGREES
                val gimbalAction = createGimbalRotateAction(
                    actionId = nextActionId++,
                    payloadPositionIndex = payloadPositionIndex,
                    pitchDegrees = pass.start.gimbalPitchDegrees,
                )
                val setupActions = buildList {
                    add(gimbalAction)
                    if (requiresSettle) add(createHoverAction(nextActionId++))
                }
                val photoAction = createPhotoAction(
                    actionId = nextActionId++,
                    payloadPositionIndex = payloadPositionIndex,
                    payloadLensType = payloadLensType,
                    fileSuffix = "P${pass.start.passIndex}_${pass.start.captureView.name}",
                )
                if (pass.isPointCapture) {
                    listOf(
                        createActionGroup(
                            groupId = nextGroupId++,
                            startIndex = pass.firstWaypointIndex,
                            endIndex = pass.lastWaypointIndex,
                            trigger = WaylineActionTrigger(WaylineActionTriggerType.REACH_POINT, 0.0, 0.0),
                            actions = buildList {
                                addAll(setupActions)
                                if (includePhotoActions) add(photoAction)
                            },
                        ),
                    )
                } else {
                    val captureTrigger = when (source.constraints.captureTriggerMode) {
                        SurveyCaptureTriggerMode.DISTANCE -> WaylineActionTrigger(
                            WaylineActionTriggerType.MULTIPLE_DISTANCE,
                            0.0,
                            requireNotNull(pass.start.captureIntervalMeters) {
                                "survey pass ${pass.start.passIndex} has no capture interval"
                            },
                        )
                        SurveyCaptureTriggerMode.TIME -> WaylineActionTrigger(
                            WaylineActionTriggerType.MULTIPLE_TIMING,
                            source.constraints.timedCaptureIntervalSeconds,
                            0.0,
                        )
                    }
                    buildList {
                        add(createActionGroup(
                            groupId = nextGroupId++,
                            startIndex = pass.firstWaypointIndex,
                            endIndex = pass.firstWaypointIndex,
                            trigger = WaylineActionTrigger(WaylineActionTriggerType.REACH_POINT, 0.0, 0.0),
                            actions = setupActions,
                        ))
                        if (includePhotoActions) {
                            add(createActionGroup(
                                groupId = nextGroupId++,
                                startIndex = pass.firstWaypointIndex,
                                endIndex = pass.lastWaypointIndex,
                                trigger = captureTrigger,
                                actions = listOf(photoAction),
                            ))
                        }
                    }
                }
            }
            realTimeFollowSurfaceIncreaseHeight = DjiWpmzRoutePolicy.realTimeTerrainIncreaseHeight(
                hasPrecomputedWaypointHeights = source.terrainPlan != null,
            )
        }
        val continuousIndices = source.waypoints.indices.filter { index ->
            source.waypoints[index].kind == SurveyWaypointKind.CAPTURE_POINT &&
                wayline.waypoints[index].turnParam.turnMode == WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE
        }.toSet()
        return DjiWpmzMission(mission, config, wayline, continuousIndices)
    }

    internal fun normalizeDjiWaypointYaw(headingDegrees: Double): Double {
        require(headingDegrees.isFinite()) { "waypoint heading must be finite" }
        var normalized = ((headingDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (normalized == -180.0 && headingDegrees > 0.0) normalized = 180.0
        return normalized
    }

    private fun createGimbalRotateAction(
        actionId: Int,
        payloadPositionIndex: Int,
        pitchDegrees: Double,
    ) = WaylineActionInfo().apply {
        this.actionId = actionId
        actionType = WaylineActionType.GIMBAL_ROTATE
        gimbalRotateParam = ActionGimbalRotateParam().apply {
            this.payloadPositionIndex = payloadPositionIndex
            rotateMode = WaylineGimbalActuatorRotateMode.ABSOLUTE_ANGLE
            enablePitch = true
            pitch = pitchDegrees
            enableRoll = false
            roll = 0.0
            enableYaw = false
            yaw = 0.0
            enableRotateTime = true
            rotateTime = CAPTURE_GIMBAL_ROTATION_SECONDS
            base = GimbalHeadingYawBase.AIRCRAFT
        }
    }

    private fun createPhotoAction(
        actionId: Int,
        payloadPositionIndex: Int,
        payloadLensType: CameraLensType,
        fileSuffix: String,
    ) = WaylineActionInfo().apply {
        this.actionId = actionId
        actionType = WaylineActionType.TAKE_PHOTO
        takePhotoParam = ActionTakePhotoParam().apply {
            this.payloadPositionIndex = payloadPositionIndex
            useGlobalPayloadLensIndex = false
            payloadLensIndex = listOf(payloadLensType)
            this.fileSuffix = fileSuffix
        }
    }

    private fun createHoverAction(actionId: Int) = WaylineActionInfo().apply {
        this.actionId = actionId
        actionType = WaylineActionType.HOVER
        aircraftHoverParam = ActionAircraftHoverParam(CAPTURE_STABILIZATION_SECONDS)
    }

    private fun createActionGroup(
        groupId: Int,
        startIndex: Int,
        endIndex: Int,
        trigger: WaylineActionTrigger,
        actions: List<WaylineActionInfo>,
    ) = WaylineActionGroup().apply {
        this.groupId = groupId
        this.startIndex = startIndex
        this.endIndex = endIndex
        this.trigger = trigger
        this.actions = actions
        nodeLists = listOf(
            WaylineActionNodeList(
                listOf(
                    WaylineActionTreeNode().apply {
                        nodeType = WaylineActionsRelationType.SEQUENCE
                        childrenNum = actions.size
                    },
                ),
            ),
            WaylineActionNodeList(
                actions.indices.map { actionIndex ->
                    WaylineActionTreeNode().apply {
                        nodeType = WaylineActionsRelationType.LEAF
                        this.actionIndex = actionIndex
                    }
                },
            ),
        )
    }

    private fun headingDifference(first: Double, second: Double): Double =
        kotlin.math.abs(((second - first) % 360.0 + 540.0) % 360.0 - 180.0)

    private const val CAPTURE_HEADING_SETTLE_THRESHOLD_DEGREES = 3.0
    private const val CAPTURE_GIMBAL_ROTATION_SECONDS = 2.0
    private const val CAPTURE_STABILIZATION_SECONDS = 2.0

}

data class DjiWpmzTurnSpec(
    val mode: WaylineWaypointTurnMode,
    val dampingMeters: Double,
)

object DjiWpmzRoutePolicy {
    fun continuousCaptureIndices(mission: SurveyMission): Set<Int> {
        if (mission.recaptureFlightMode != edu.playground.djivln.survey.RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL) {
            return emptySet()
        }
        return DjiWaylinePartition.segments(mission).flatMap { segment ->
            segment.mission.waypoints.indices.filter { index ->
                segment.mission.waypoints[index].kind == SurveyWaypointKind.CAPTURE_POINT &&
                    turn(segment.mission.waypoints, index, true, true).mode ==
                    WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE
            }.map { segment.globalWaypointIndex(it) }
        }.toSet()
    }

    fun turn(
        waypoints: List<SurveyWaypoint>,
        index: Int,
        allowTransitPassThrough: Boolean,
        allowCapturePassThrough: Boolean = false,
    ): DjiWpmzTurnSpec {
        val stop = DjiWpmzTurnSpec(
            WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE,
            0.0,
        )
        val waypoint = waypoints.getOrNull(index) ?: return stop
        if (index == 0 || index == waypoints.lastIndex) return stop
        val continuousCapture = allowCapturePassThrough && waypoint.kind == SurveyWaypointKind.CAPTURE_POINT
        if (continuousCapture) {
            val previous = waypoints[index - 1]
            val next = waypoints[index + 1]
            val incomingBearing = bearing(previous.point, waypoint.point)
            val outgoingBearing = bearing(waypoint.point, next.point)
            if (angleDifference(incomingBearing, outgoingBearing) > 30.0 ||
                angleDifference(previous.headingDegrees, waypoint.headingDegrees) > 5.0 ||
                angleDifference(waypoint.headingDegrees, next.headingDegrees) > 5.0 ||
                kotlin.math.abs(previous.gimbalPitchDegrees - waypoint.gimbalPitchDegrees) > 3.0 ||
                kotlin.math.abs(next.gimbalPitchDegrees - waypoint.gimbalPitchDegrees) > 3.0 ||
                kotlin.math.abs(previous.point.altitudeMeters - waypoint.point.altitudeMeters) > 0.5 ||
                kotlin.math.abs(next.point.altitudeMeters - waypoint.point.altitudeMeters) > 0.5
            ) return stop
        } else if (!allowTransitPassThrough || waypoint.kind != SurveyWaypointKind.TRANSIT) return stop

        val incomingDistance = distanceMeters(waypoints[index - 1].point, waypoint.point)
        val outgoingDistance = distanceMeters(waypoint.point, waypoints[index + 1].point)
        val dampingMeters = minOf(
            MAX_TRANSIT_DAMPING_METERS,
            incomingDistance * DAMPING_LEG_FRACTION,
            outgoingDistance * DAMPING_LEG_FRACTION,
        )
        if (!dampingMeters.isFinite() || dampingMeters < MIN_TRANSIT_DAMPING_METERS) return stop
        return DjiWpmzTurnSpec(
            WaylineWaypointTurnMode.TO_POINT_AND_PASS_WITH_CONTINUITY_CURVATURE,
            dampingMeters,
        )
    }

    internal fun distanceMeters(start: GeoPoint, end: GeoPoint): Double {
        val north = (end.latitude - start.latitude) * 111_132.0
        val east = (end.longitude - start.longitude) * 111_320.0 *
            kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
        return kotlin.math.hypot(north, east)
    }

    private fun bearing(start: GeoPoint, end: GeoPoint): Double = Math.toDegrees(kotlin.math.atan2(
        (end.longitude - start.longitude) * kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0)),
        end.latitude - start.latitude,
    ))

    private fun angleDifference(first: Double, second: Double): Double =
        kotlin.math.abs(((second - first) % 360.0 + 540.0) % 360.0 - 180.0)

    fun realTimeTerrainIncreaseHeight(hasPrecomputedWaypointHeights: Boolean): Boolean = false

    private const val DAMPING_LEG_FRACTION = 0.2
    private const val MIN_TRANSIT_DAMPING_METERS = 0.2
    private const val MAX_TRANSIT_DAMPING_METERS = 1.0
}

class DjiWpmzPackageWriter(context: Context) {
    private val appContext = context.applicationContext
    private val manager = WPMZManager.getInstance().also { it.init(appContext) }

    fun generate(
        source: SurveyMission,
        destination: File,
        globalRthHeightMeters: Double,
        payloadPositionIndex: Int,
        payloadLensType: CameraLensType,
    ): WpmzValidationResult {
        destination.parentFile?.mkdirs()
        val includePhotoActions = DjiCameraProfileCatalog.waylineCaptureStrategy(source.cameraProfile) ==
            DjiWaylineCaptureStrategy.WPML_PHOTO_ACTION
        val segments = DjiWaylinePartition.segments(source, appContext)
        val segmentCompletionAction = if (segments.size > 1) {
            SurveyCompletionAction.HOVER
        } else {
            source.constraints.completionAction
        }
        val segmentFiles = mutableListOf<File>()
        try {
            segments.forEach { segment ->
                val segmentMission = segment.mission.copy(
                    constraints = segment.mission.constraints.copy(
                        completionAction = segmentCompletionAction,
                    ),
                )
                val converted = SurveyWpmzConverter.convert(
                    segmentMission,
                    globalRthHeightMeters = globalRthHeightMeters,
                    payloadPositionIndex = payloadPositionIndex,
                    payloadLensType = payloadLensType,
                    includePhotoActions = includePhotoActions,
                    waylineId = segment.waylineId,
                )
                val contractErrors = DjiWpmzContractValidator.validate(converted)
                require(contractErrors.isEmpty()) { contractErrors.joinToString("; ") }
                val part = File(
                    destination.parentFile,
                    "${destination.nameWithoutExtension}-part-${segment.waylineId}.kmz",
                ).also(File::delete)
                manager.generateKMZFile(
                    part.absolutePath,
                    converted.mission,
                    converted.config,
                    converted.wayline,
                )
                require(part.isFile && part.length() > 0L) {
                    appContext.getString(R.string.wpmz_segment_kmz_not_generated)
                }
                segmentFiles += part
            }
            if (segmentFiles.size == 1) {
                segmentFiles.single().copyTo(destination, overwrite = true)
            } else {
                mergeWaylineArchives(segmentFiles, destination)
            }
        } finally {
            segmentFiles.forEach(File::delete)
        }
        require(destination.isFile && destination.length() > 0L) {
            appContext.getString(R.string.wpmz_kmz_not_generated)
        }
        val validation = manager.checkValidation(destination.absolutePath)
        val errors = validation?.value.orEmpty() + DjiWpmzContractValidator.validateArchive(
            destination,
            requirePhotoActions = includePhotoActions,
        )
        return WpmzValidationResult(
            valid = errors.isEmpty(),
            details = if (errors.isEmpty()) "WPMZ SDK validation passed" else errors.joinToString(),
        )
    }

    private fun mergeWaylineArchives(parts: List<File>, destination: File) {
        val entries = linkedMapOf<String, ByteArray>()
        parts.first().inputStream().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries[entry.name] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
        }
        val folders = parts.map { part ->
            ZipFile(part).use { zip ->
                val entry = requireNotNull(zip.getEntry(WAYLINES_ENTRY)) {
                    appContext.getString(R.string.segment_kmz_entry_missing, WAYLINES_ENTRY)
                }
                val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                requireNotNull(FOLDER_PATTERN.find(xml)?.value) {
                    appContext.getString(R.string.segment_kmz_wayline_folder_missing)
                }
            }
        }
        val baseXml = requireNotNull(entries[WAYLINES_ENTRY]) {
            appContext.getString(R.string.kmz_entry_missing, WAYLINES_ENTRY)
        }.toString(Charsets.UTF_8)
        val firstFolder = requireNotNull(FOLDER_PATTERN.find(baseXml)) {
            appContext.getString(R.string.kmz_wayline_folder_missing)
        }
        entries[WAYLINES_ENTRY] = baseXml.replaceRange(
            firstFolder.range,
            folders.joinToString("\n    "),
        ).toByteArray(Charsets.UTF_8)
        FileOutputStream(destination).use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private companion object {
        const val WAYLINES_ENTRY = "wpmz/waylines.wpml"
        val FOLDER_PATTERN = Regex("<Folder>.*?</Folder>", setOf(RegexOption.DOT_MATCHES_ALL))
    }
}
