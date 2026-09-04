package edu.playground.djivln.survey

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

object SurveyPlanner {
    data class Coverage(
        val footprintWidthMeters: Double,
        val footprintLengthMeters: Double,
        val lineSpacingMeters: Double,
        val captureIntervalMeters: Double,
        val groundSampleDistanceCentimeters: Double,
    )

    data class GroundCoverage(
        val boundary: List<GeoPoint>,
        val areaSquareMeters: Double,
    )

    /**
     * Fits a minimum-area oriented rectangle to the ROI and returns the
     * aviation heading of its long side. Route direction is an axis, so the
     * result is normalized to [0, 180).
     */
    fun suggestedRouteHeading(roi: List<GeoPoint>): Double {
        val normalized = normalizeAndValidateRoi(roi)
        val frame = LocalFrame(centroid(normalized))
        val polygon = normalized.map(frame::toLocal)
        var bestArea = Double.POSITIVE_INFINITY
        var bestLongSpan = Double.NEGATIVE_INFINITY
        var bestHeading = 0.0
        polygon.indices.forEach { index ->
            val start = polygon[index]
            val end = polygon[(index + 1) % polygon.size]
            if (distance(start, end) < 1.0e-6) return@forEach
            val edgeHeading = headingDegrees(start, end)
            val angle = Math.toRadians(90.0 - edgeHeading)
            val rotated = polygon.map { rotate(it, -angle) }
            val alongSpan = rotated.maxOf { it.eastMeters } - rotated.minOf { it.eastMeters }
            val crossSpan = rotated.maxOf { it.northMeters } - rotated.minOf { it.northMeters }
            val area = alongSpan * crossSpan
            val longSpan = max(alongSpan, crossSpan)
            val longHeading = normalizeHeading(
                edgeHeading + if (alongSpan >= crossSpan) 0.0 else 90.0,
            ) % 180.0
            if (area < bestArea - 1.0e-6 ||
                abs(area - bestArea) <= 1.0e-6 && longSpan > bestLongSpan
            ) {
                bestArea = area
                bestLongSpan = longSpan
                bestHeading = longHeading
            }
        }
        return bestHeading
    }

    data class TargetArea(
        val boundary: List<GeoPoint>,
        val areaSquareMeters: Double,
    )

    data class CaptureFeasibility(
        val feasible: Boolean,
        val requestedIntervalSeconds: Double,
        val minimumIntervalSeconds: Double,
        val maximumFeasibleSpeedMetersPerSecond: Double,
    )

    data class SpeedLimit(
        val hardMaximumMetersPerSecond: Double,
        val cameraMaximumMetersPerSecond: Double,
        val effectiveMaximumMetersPerSecond: Double,
        val cameraLimited: Boolean,
        val exceeded: Boolean,
    )

    data class MissionStatistics(
        val passCountByView: Map<SurveyCaptureView, Int>,
        val photoCountByView: Map<SurveyCaptureView, Int>,
        val estimatedStorageMegabytes: Double,
        val estimatedSorties: Int,
        val assumedJpegMegabytes: Double,
        val assumedUsableSortieSeconds: Double,
        val sorties: List<SurveySortie>,
        val operationalPathMeters: Double,
        val operationalFlightSeconds: Double,
        val transitAndCompletionSeconds: Double,
        val operationalReferenceAvailable: Boolean,
    )

    data class SurveySortie(
        val sortieNumber: Int,
        val firstWaypointIndex: Int,
        val lastWaypointIndex: Int,
        val estimatedPathMeters: Double,
        val estimatedFlightSeconds: Double,
        val estimatedPhotoCount: Int,
    )

    fun coverage(camera: CameraProfile, constraints: SurveyConstraints): Coverage {
        val altitude = constraints.altitudeMetersAgl
        val width = 2.0 * altitude * tan(Math.toRadians(camera.horizontalFieldOfViewDegrees / 2.0))
        val length = 2.0 * altitude * tan(Math.toRadians(camera.verticalFieldOfViewDegrees / 2.0))
        return Coverage(
            footprintWidthMeters = width,
            footprintLengthMeters = length,
            lineSpacingMeters = max(0.5, width * (1.0 - constraints.sideOverlap)),
            captureIntervalMeters = max(0.5, length * (1.0 - constraints.forwardOverlap)),
            groundSampleDistanceCentimeters = width / camera.imageWidthPixels * 100.0,
        )
    }

    /** Converts a desired nadir GSD to the required height above target. */
    fun altitudeForGroundSampleDistance(
        camera: CameraProfile,
        groundSampleDistanceCentimeters: Double,
    ): Double {
        require(groundSampleDistanceCentimeters > 0.0 && groundSampleDistanceCentimeters.isFinite()) {
            "GSD must be positive"
        }
        val footprintWidthMeters = groundSampleDistanceCentimeters / 100.0 * camera.imageWidthPixels
        return footprintWidthMeters /
            (2.0 * tan(Math.toRadians(camera.horizontalFieldOfViewDegrees / 2.0)))
    }

    /**
     * Perspective ground envelope for a forward-looking oblique image over a
     * locally flat target surface. It intentionally rejects horizon-crossing
     * camera poses instead of returning an unbounded footprint.
     */
    fun obliqueCoverage(camera: CameraProfile, constraints: SurveyConstraints): Coverage {
        val offNadirDegrees = abs(90.0 + constraints.obliqueGimbalPitchDegrees)
        val halfVerticalDegrees = camera.verticalFieldOfViewDegrees / 2.0
        require(offNadirDegrees + halfVerticalDegrees < 89.0) {
            "oblique camera field of view reaches the horizon"
        }
        val altitude = constraints.altitudeMetersAgl
        val near = altitude * tan(Math.toRadians(max(0.0, offNadirDegrees - halfVerticalDegrees)))
        val far = altitude * tan(Math.toRadians(offNadirDegrees + halfVerticalDegrees))
        val centerSlantRange = altitude / cos(Math.toRadians(offNadirDegrees))
        val width = 2.0 * centerSlantRange *
            tan(Math.toRadians(camera.horizontalFieldOfViewDegrees / 2.0))
        val length = far - near
        return Coverage(
            footprintWidthMeters = width,
            footprintLengthMeters = length,
            lineSpacingMeters = max(0.5, width * (1.0 - constraints.obliqueSideOverlap)),
            captureIntervalMeters = max(0.5, length * (1.0 - constraints.obliqueForwardOverlap)),
            groundSampleDistanceCentimeters = width / camera.imageWidthPixels * 100.0,
        )
    }

    fun captureFeasibility(
        camera: CameraProfile,
        constraints: SurveyConstraints,
        oblique: Boolean = false,
    ): CaptureFeasibility {
        val plannedCoverage = if (oblique) obliqueCoverage(camera, constraints)
            else coverage(camera, constraints)
        val requestedIntervalSeconds = when (constraints.captureTriggerMode) {
            SurveyCaptureTriggerMode.DISTANCE ->
                plannedCoverage.captureIntervalMeters / if (oblique) {
                    constraints.obliqueSpeedMetersPerSecond
                } else {
                    constraints.speedMetersPerSecond
                }
            SurveyCaptureTriggerMode.TIME -> constraints.timedCaptureIntervalSeconds
        }
        val minimumIntervalSeconds = calibratedMinimumCaptureIntervalSeconds(camera)
        return CaptureFeasibility(
            feasible = requestedIntervalSeconds + 1.0e-9 >= minimumIntervalSeconds,
            requestedIntervalSeconds = requestedIntervalSeconds,
            minimumIntervalSeconds = minimumIntervalSeconds,
            maximumFeasibleSpeedMetersPerSecond = plannedCoverage.captureIntervalMeters /
                minimumIntervalSeconds,
        )
    }

    private fun calibratedMinimumCaptureIntervalSeconds(camera: CameraProfile): Double {
        val official = DjiCameraProfileCatalog.allVerified()
            .firstOrNull { it.profile.id == camera.id }
            ?.profile
        return max(camera.minimumCaptureIntervalSeconds, official?.minimumCaptureIntervalSeconds ?: 0.0)
    }

    /** Single source of truth for planner/UI/executor survey horizontal speed limits. */
    fun speedLimit(camera: CameraProfile, constraints: SurveyConstraints): SpeedLimit {
        val hardMaximum = 10.0
        val nadirMaximum = if (constraints.captureTriggerMode == SurveyCaptureTriggerMode.TIME) {
            Double.POSITIVE_INFINITY
        } else {
            captureFeasibility(camera, constraints, oblique = false).maximumFeasibleSpeedMetersPerSecond
        }
        val obliqueMaximum = if (constraints.captureTriggerMode == SurveyCaptureTriggerMode.TIME) {
            Double.POSITIVE_INFINITY
        } else {
            captureFeasibility(camera, constraints, oblique = true).maximumFeasibleSpeedMetersPerSecond
        }
        val usesNadir = constraints.collectionMode != SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION ||
            SurveyCaptureView.NADIR in constraints.enabledCaptureViews
        val usesOblique = constraints.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION &&
            constraints.enabledCaptureViews.any { it != SurveyCaptureView.NADIR }
        val cameraMaximum = listOfNotNull(
            nadirMaximum.takeIf { usesNadir },
            obliqueMaximum.takeIf { usesOblique },
        ).minOrNull() ?: hardMaximum
        val effective = minOf(hardMaximum, cameraMaximum)
        return SpeedLimit(
            hardMaximumMetersPerSecond = hardMaximum,
            cameraMaximumMetersPerSecond = cameraMaximum,
            effectiveMaximumMetersPerSecond = effective,
            cameraLimited = cameraMaximum < hardMaximum,
            exceeded = usesNadir &&
                constraints.speedMetersPerSecond > nadirMaximum.coerceAtMost(hardMaximum) + 1.0e-9 ||
                (usesOblique &&
                    constraints.obliqueSpeedMetersPerSecond > obliqueMaximum.coerceAtMost(hardMaximum) + 1.0e-9),
        )
    }

    fun statistics(
        mission: SurveyMission,
        assumedJpegMegabytes: Double = 5.0,
        assumedUsableSortieSeconds: Double = 20.0 * 60.0,
        launchPoint: GeoPoint? = null,
    ): MissionStatistics {
        require(assumedJpegMegabytes > 0.0) { "assumed JPEG size must be positive" }
        require(assumedUsableSortieSeconds > 0.0) { "sortie duration must be positive" }
        val passCounts = linkedMapOf<SurveyCaptureView, Int>()
        mission.surveyPasses().filterNot { it.isTransitOnly }.forEach { pass ->
            val start = pass.start
            passCounts[start.captureView] = (passCounts[start.captureView] ?: 0) + 1
        }
        val photoCounts = SurveyCaptureSchedule.build(mission)
            .groupingBy { it.captureView }
            .eachCount()
        val totalPhotos = photoCounts.values.sum()
        val sortiePlan = planSorties(mission, assumedUsableSortieSeconds)
        val operational = estimateOperationalPath(mission, launchPoint)
        return MissionStatistics(
            passCountByView = passCounts,
            photoCountByView = photoCounts,
            estimatedStorageMegabytes = totalPhotos * assumedJpegMegabytes,
            estimatedSorties = sortiePlan.size,
            assumedJpegMegabytes = assumedJpegMegabytes,
            assumedUsableSortieSeconds = assumedUsableSortieSeconds,
            sorties = sortiePlan,
            operationalPathMeters = operational.first,
            operationalFlightSeconds = operational.second,
            transitAndCompletionSeconds = max(0.0, operational.second - mission.estimatedFlightSeconds),
            operationalReferenceAvailable = launchPoint != null,
        )
    }

    private fun estimateOperationalPath(
        mission: SurveyMission,
        launchPoint: GeoPoint?,
    ): Pair<Double, Double> {
        if (launchPoint == null) return mission.estimatedPathMeters to mission.estimatedFlightSeconds
        val frame = LocalFrame(centroid(mission.roi))
        val legs = SurveySimulatorExecutionStateMachine.buildExecutionLegs(mission, launchPoint)
        var previous: GeoPoint = launchPoint
        var previousTarget: SurveyWaypoint? = null
        var horizontalMeters = 0.0
        var seconds = 0.0
        legs.forEach { leg ->
            val horizontal = distance(frame.toLocal(previous), frame.toLocal(leg.target.point))
            val vertical = abs(leg.target.point.altitudeMeters - previous.altitudeMeters)
            horizontalMeters += horizontal
            val horizontalSeconds = horizontal /
                mission.constraints.speedForCaptureView(leg.target.captureView)
            val verticalSpeed = if (leg.target.point.altitudeMeters < previous.altitudeMeters) {
                mission.constraints.descentSpeedMetersPerSecond
            } else mission.constraints.takeoffSpeedMetersPerSecond
            var legSeconds = max(horizontalSeconds, vertical / verticalSpeed)
            previousTarget?.let { before ->
                val yaw = abs(((leg.target.headingDegrees - before.headingDegrees) % 360.0 + 540.0) % 360.0 - 180.0)
                if (yaw > SurveyWaypointFollower.HEADING_TOLERANCE_DEGREES) {
                    legSeconds += 2.0 + yaw / SurveyWaypointFollower.MAX_YAW_RATE_DEGREES_PER_SECOND
                }
                if (abs(before.gimbalPitchDegrees - leg.target.gimbalPitchDegrees) > 5.0) {
                    legSeconds += 3.0
                }
            }
            legSeconds += SurveyEtaPolicy.captureDelaySeconds(leg.target.captureAction)
            seconds += legSeconds
            previous = leg.target.point
            previousTarget = leg.target
        }
        return horizontalMeters to seconds
    }

    /** Splits only between complete photo passes; a capture interval is never cut in half. */
    fun planSorties(
        mission: SurveyMission,
        usableSortieSeconds: Double = 20.0 * 60.0,
    ): List<SurveySortie> {
        require(usableSortieSeconds > 0.0 && usableSortieSeconds.isFinite()) {
            "usable sortie duration must be positive"
        }
        val frame = LocalFrame(centroid(mission.roi))
        data class PassEstimate(
            val firstIndex: Int,
            val lastIndex: Int,
            val start: LocalPoint,
            val end: LocalPoint,
            val pathMeters: Double,
            val photos: Int,
            val startWaypoint: SurveyWaypoint,
            val endWaypoint: SurveyWaypoint,
            val seconds: Double,
        )
        val photoCountsByPass = SurveyCaptureSchedule.build(mission)
            .groupingBy { it.passIndex }
            .eachCount()
        val passes = mission.surveyPasses().map { pass ->
            val start = frame.toLocal(pass.start.point)
            val end = frame.toLocal(pass.end.point)
            val path = pass.waypoints.zipWithNext().sumOf { (a, b) ->
                distance(frame.toLocal(a.point), frame.toLocal(b.point))
            }
            PassEstimate(
                firstIndex = pass.firstWaypointIndex,
                lastIndex = pass.lastWaypointIndex,
                start = start,
                end = end,
                pathMeters = path,
                photos = photoCountsByPass[pass.start.passIndex] ?: 0,
                startWaypoint = pass.start,
                endWaypoint = pass.end,
                seconds = estimateRouteSeconds(pass.waypoints, mission.constraints),
            )
        }
        if (passes.isEmpty()) return emptyList()
        val result = mutableListOf<SurveySortie>()
        var firstIndex = -1
        var lastIndex = -1
        var pathMeters = 0.0
        var flightSeconds = 0.0
        var photos = 0
        var previousEnd: LocalPoint? = null
        var previousWaypoint: SurveyWaypoint? = null
        fun flush() {
            if (firstIndex < 0) return
            result += SurveySortie(
                sortieNumber = result.size + 1,
                firstWaypointIndex = firstIndex,
                lastWaypointIndex = lastIndex,
                estimatedPathMeters = pathMeters,
                estimatedFlightSeconds = flightSeconds,
                estimatedPhotoCount = photos,
            )
            firstIndex = -1
            lastIndex = -1
            pathMeters = 0.0
            flightSeconds = 0.0
            photos = 0
            previousEnd = null
            previousWaypoint = null
        }
        passes.forEach { pass ->
            val transitionSeconds = previousWaypoint?.let {
                estimateRouteSeconds(listOf(it, pass.startWaypoint), mission.constraints) -
                    SurveyEtaPolicy.captureDelaySeconds(pass.startWaypoint.captureAction)
            } ?: 0.0
            val additionSeconds = transitionSeconds + pass.seconds
            if (firstIndex >= 0 && flightSeconds + additionSeconds > usableSortieSeconds) flush()
            if (firstIndex < 0) firstIndex = pass.firstIndex
            val actualTransition = previousEnd?.let { distance(it, pass.start) } ?: 0.0
            pathMeters += actualTransition + pass.pathMeters
            flightSeconds += transitionSeconds + pass.seconds
            photos += pass.photos
            lastIndex = pass.lastIndex
            previousEnd = pass.end
            previousWaypoint = pass.endWaypoint
        }
        flush()
        return result
    }

    fun plan(
        name: String,
        roi: List<GeoPoint>,
        // Schema/test compatibility only. Both Android UIs pass their detected live profile.
        camera: CameraProfile = CameraProfile.DJI_MINI_2,
        constraints: SurveyConstraints = SurveyConstraints(),
        /** Current aircraft/home/custom point used only to choose a route corner. */
        takeoffPoint: GeoPoint? = null,
    ): SurveyMission {
        val planningRoi = normalizeAndValidateRoi(roi)

        val origin = centroid(planningRoi)
        val frame = LocalFrame(origin)
        val polygon = planningRoi.map(frame::toLocal)
        val nadirCoverage = coverage(camera, constraints)
        val extentEast = polygon.maxOf { it.eastMeters } - polygon.minOf { it.eastMeters }
        val extentNorth = polygon.maxOf { it.northMeters } - polygon.minOf { it.northMeters }
        require(hypot(extentEast, extentNorth) <= 10_000.0) {
            "ROI extent exceeds the 10 km planning safety limit"
        }

        val routeGroups = routeGroups(constraints)
        val passes = mutableListOf<PlannedPass>()
        var orderingReference = takeoffPoint?.let(frame::toLocal)
        routeGroups.forEachIndexed { groupIndex, group ->
            val groupCoverage = if (group.captureView == SurveyCaptureView.NADIR) {
                nadirCoverage
            } else {
                obliqueCoverage(camera, constraints)
            }
            val generated = generatePasses(
                polygon,
                group.routeHeadingDegrees,
                groupCoverage.lineSpacingMeters,
                groupCoverage.footprintWidthMeters,
                constraints.boundaryMarginMeters,
            )
            val startMode = if (groupIndex == 0) constraints.startPointMode
                else SurveyStartPointMode.AUTO_NEAREST
            val ordered = orientPasses(generated, startMode, orderingReference)
            passes += ordered.map { PlannedPass(it, group, groupCoverage) }
            orderingReference = ordered.lastOrNull()?.end ?: orderingReference
        }
        require(passes.isNotEmpty()) { "ROI is too small for the selected survey settings" }

        val waypoints = mutableListOf<SurveyWaypoint>()
        var pathMeters = 0.0
        var photos = 0
        var previous: LocalPoint? = null
        passes.forEachIndexed { passIndex, planned ->
            val pass = planned.pass
            val routeHeading = headingDegrees(pass.start, pass.end)
            val heading = if (
                constraints.obliqueHeadingMode == SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION
            ) planned.group.fixedCameraHeadingDegrees ?: routeHeading else routeHeading
            pathMeters += previous?.let { distance(it, pass.start) } ?: 0.0
            val passLength = distance(pass.start, pass.end)
            pathMeters += passLength
            val captureInterval = when (constraints.captureTriggerMode) {
                SurveyCaptureTriggerMode.DISTANCE -> planned.coverage.captureIntervalMeters
                SurveyCaptureTriggerMode.TIME -> constraints.speedForCaptureView(planned.group.captureView) *
                    constraints.timedCaptureIntervalSeconds
            }
            photos += max(2, ceil(passLength / captureInterval).toInt() + 1)
            waypoints += SurveyWaypoint(
                point = frame.toGeo(pass.start, constraints.effectiveFlightAltitudeMeters),
                headingDegrees = heading,
                gimbalPitchDegrees = planned.group.gimbalPitchDegrees,
                kind = SurveyWaypointKind.PASS_START,
                captureAction = CaptureAction.START_DISTANCE_INTERVAL,
                captureIntervalMeters = captureInterval,
                passIndex = passIndex,
                captureView = planned.group.captureView,
            )
            waypoints += SurveyWaypoint(
                point = frame.toGeo(pass.end, constraints.effectiveFlightAltitudeMeters),
                headingDegrees = heading,
                gimbalPitchDegrees = planned.group.gimbalPitchDegrees,
                kind = SurveyWaypointKind.PASS_END,
                captureAction = CaptureAction.STOP_DISTANCE_INTERVAL,
                captureIntervalMeters = null,
                passIndex = passIndex,
                captureView = planned.group.captureView,
            )
            previous = pass.end
        }

        return SurveyMission(
            name = name,
            cameraProfile = camera,
            constraints = constraints,
            roi = planningRoi,
            waypoints = waypoints,
            estimatedPathMeters = pathMeters,
            estimatedPhotoCount = photos,
            estimatedFlightSeconds = estimateRouteSeconds(waypoints, constraints),
        )
    }

    internal fun estimateRouteSeconds(
        waypoints: List<SurveyWaypoint>,
        constraints: SurveyConstraints,
    ): Double {
        var total = waypoints.firstOrNull()?.let {
            SurveyEtaPolicy.captureDelaySeconds(it.captureAction)
        } ?: 0.0
        waypoints.zipWithNext().forEach { (start, end) ->
            var seconds = geographicDistance(start.point, end.point) /
                constraints.speedForCaptureView(end.captureView).coerceAtLeast(0.2)
            val yaw = abs(((end.headingDegrees - start.headingDegrees) % 360.0 + 540.0) % 360.0 - 180.0)
            if (yaw > SurveyWaypointFollower.HEADING_TOLERANCE_DEGREES) {
                seconds += 2.0 + yaw / SurveyWaypointFollower.MAX_YAW_RATE_DEGREES_PER_SECOND
            }
            if (abs(start.gimbalPitchDegrees - end.gimbalPitchDegrees) > 5.0) seconds += 3.0
            seconds += SurveyEtaPolicy.captureDelaySeconds(end.captureAction)
            total += seconds
        }
        return total
    }

    private fun geographicDistance(a: GeoPoint, b: GeoPoint): Double {
        val meanLatitude = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val north = (b.latitude - a.latitude) * 111_132.0
        val east = (b.longitude - a.longitude) * 111_320.0 * cos(meanLatitude)
        return hypot(north, east)
    }

    /**
     * Returns the actual nadir-image footprint envelope, not merely the user
     * ROI or the flight-line centre path. Each pass is swept by the camera
     * footprint, including half a frame before its start and after its end.
     */
    fun groundCoverage(mission: SurveyMission): GroundCoverage {
        require(mission.waypoints.isNotEmpty()) { "mission requires survey waypoints" }
        val origin = centroid(mission.roi)
        val frame = LocalFrame(origin)
        val coverage = coverage(mission.cameraProfile, mission.constraints)
        val waypointPairs = mission.surveyPasses()
        val coveragePairs = if (mission.constraints.collectionMode == SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION) {
            val nadir = waypointPairs.filter { it.start.captureView == SurveyCaptureView.NADIR }
            if (nadir.isNotEmpty()) nadir else {
                // A valid selected-group mission may intentionally omit nadir. Use one complete
                // selected view for a stable preview envelope instead of rejecting the mission.
                val previewView = waypointPairs.first().start.captureView
                waypointPairs.filter { it.start.captureView == previewView }
            }
        } else {
            waypointPairs
        }
        val footprints = coveragePairs.mapNotNull { pass ->
            val start = frame.toLocal(pass.start.point)
            val end = frame.toLocal(pass.end.point)
            if (pass.isPointCapture) {
                GeometryFactory().createPoint(Coordinate(start.eastMeters, start.northMeters))
                    .buffer(max(coverage.footprintWidthMeters, coverage.footprintLengthMeters) / 2.0)
            } else {
                passFootprint(
                    start,
                    end,
                    coverage.footprintWidthMeters / 2.0,
                    coverage.footprintLengthMeters / 2.0,
                )
            }
        }
        require(footprints.isNotEmpty()) { "mission has no measurable survey passes" }
        val union = UnaryUnionOp.union(footprints)
        val polygon = largestPolygon(union)
        return GroundCoverage(
            boundary = polygon.exteriorRing.coordinates.dropLast(1).map {
                frame.toGeo(LocalPoint(it.x, it.y), mission.constraints.altitudeMetersAgl)
            },
            areaSquareMeters = union.area,
        )
    }

    /** The user ROI after applying the explicit DJI-style outward margin. */
    fun targetArea(mission: SurveyMission): TargetArea {
        val origin = centroid(mission.roi)
        val frame = LocalFrame(origin)
        val expanded = expandPolygon(
            mission.roi.map(frame::toLocal),
            mission.constraints.boundaryMarginMeters,
        )
        val polygon = GeometryFactory().createPolygon(
            (expanded + expanded.first()).map {
                Coordinate(it.eastMeters, it.northMeters)
            }.toTypedArray(),
        )
        return TargetArea(
            boundary = expanded.map {
                frame.toGeo(it, mission.constraints.altitudeMetersAgl)
            },
            areaSquareMeters = polygon.area,
        )
    }

    private data class LocalPass(val start: LocalPoint, val end: LocalPoint, val scanLineIndex: Int)

    private data class RouteGroup(
        val routeHeadingDegrees: Double,
        val fixedCameraHeadingDegrees: Double?,
        val gimbalPitchDegrees: Double,
        val captureView: SurveyCaptureView,
    )

    private data class PlannedPass(
        val pass: LocalPass,
        val group: RouteGroup,
        val coverage: Coverage,
    )

    private fun orientPasses(
        passes: List<LocalPass>,
        mode: SurveyStartPointMode,
        reference: LocalPoint?,
    ): List<LocalPass> {
        if (passes.isEmpty()) return passes
        fun reversedLine(line: List<LocalPass>) = line.asReversed().map {
            LocalPass(it.end, it.start, it.scanLineIndex)
        }
        val lines = passes.groupBy { it.scanLineIndex }.values.toList()
        fun rows(values: List<List<LocalPass>>, flip: Boolean) = values.flatMap {
            if (flip) reversedLine(it) else it
        }
        val candidates = listOf(
            rows(lines, false),
            rows(lines, true),
            rows(lines.asReversed(), false),
            rows(lines.asReversed(), true),
        ).let { rowCandidates ->
            if (lines.none { it.size > 1 }) rowCandidates else rowCandidates.map { candidate ->
                // A concave scanline can contain disconnected segments. A strict row snake
                // repeatedly crosses the same indentation and can create kilometre transits.
                // Keep the selected corner, then visit the nearest remaining segment/end.
                val remaining = passes.toMutableList()
                val result = mutableListOf<LocalPass>()
                var selected = candidate.first()
                val seedIndex = remaining.indexOfFirst {
                    it.scanLineIndex == selected.scanLineIndex &&
                        ((it.start == selected.start && it.end == selected.end) ||
                            (it.start == selected.end && it.end == selected.start))
                }
                check(seedIndex >= 0) { "selected survey segment is missing" }
                remaining.removeAt(seedIndex)
                result += selected
                while (remaining.isNotEmpty()) {
                    val endpoint = selected.end
                    val next = remaining.minWithOrNull(compareBy<LocalPass> {
                        minOf(distance(endpoint, it.start), distance(endpoint, it.end))
                    }.thenBy { abs(it.scanLineIndex - selected.scanLineIndex) })!!
                    remaining.remove(next)
                    selected = if (distance(endpoint, next.start) <= distance(endpoint, next.end)) next
                    else LocalPass(next.end, next.start, next.scanLineIndex)
                    result += selected
                }
                // Remove metric crossings/local traps while preserving the chosen first
                // segment. Reversing a subsequence also reverses every segment direction.
                if (result.size <= 512) {
                    var active = true
                    repeat(if (result.size <= 128) 128 else 12) {
                        if (!active) return@repeat
                        var improved = false
                        loop@ for (left in 1 until result.size) {
                            for (right in left until result.size) {
                                val before = result[left - 1]
                                val first = result[left]
                                val last = result[right]
                                val after = result.getOrNull(right + 1)
                                val old = distance(before.end, first.start) +
                                    (after?.let { distance(last.end, it.start) } ?: 0.0)
                                val replacement = distance(before.end, last.end) +
                                    (after?.let { distance(first.start, it.start) } ?: 0.0)
                                if (replacement + 0.01 < old) {
                                    val reversed = result.subList(left, right + 1).asReversed().map {
                                        LocalPass(it.end, it.start, it.scanLineIndex)
                                    }
                                    reversed.forEachIndexed { offset, pass -> result[left + offset] = pass }
                                    improved = true
                                    break@loop
                                }
                            }
                        }
                        active = improved
                    }
                }
                result
            }
        }
        return when (mode) {
            SurveyStartPointMode.FIRST_ROUTE_START -> candidates[0]
            SurveyStartPointMode.ROUTE_CORNER_2 -> candidates[1]
            SurveyStartPointMode.ROUTE_CORNER_3 -> candidates[2]
            SurveyStartPointMode.ROUTE_CORNER_4 -> candidates[3]
            SurveyStartPointMode.AUTO_NEAREST -> if (reference == null) candidates.minByOrNull(::connectorDistance)!! else {
                candidates.minByOrNull { candidate ->
                    distance(reference, candidate.first().start) + connectorDistance(candidate)
                } ?: candidates[0]
            }
            SurveyStartPointMode.CUSTOM -> if (reference == null) candidates[0] else {
                candidates.minByOrNull { distance(reference, it.first().start) } ?: candidates[0]
            }
        }
    }

    private fun connectorDistance(passes: List<LocalPass>): Double = passes.zipWithNext().sumOf { (a, b) ->
        distance(a.end, b.start)
    }

    private fun routeGroups(constraints: SurveyConstraints): List<RouteGroup> {
        val base = normalizeHeading(constraints.routeHeadingDegrees)
        fun group(
            routeHeading: Double,
            cameraHeading: Double?,
            pitch: Double,
            view: SurveyCaptureView,
        ) = RouteGroup(
            normalizeHeading(routeHeading),
            cameraHeading?.let(::normalizeHeading),
            pitch,
            view,
        )
        return when (constraints.collectionMode) {
            SurveyCollectionMode.ORTHO -> listOf(
                group(base, base, -90.0, SurveyCaptureView.NADIR),
            )
            SurveyCollectionMode.CROSSHATCH_NADIR -> listOf(
                group(base, base, constraints.gimbalPitchDegrees, SurveyCaptureView.NADIR),
                group(base + 90.0, base + 90.0, constraints.gimbalPitchDegrees, SurveyCaptureView.NADIR),
            )
            SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION -> listOf(
                // Nadir also needs a group-wide yaw in fixed-direction mode; null
                // falls back to each pass bearing and forces 180-degree U-turns.
                group(base, base, -90.0, SurveyCaptureView.NADIR),
                group(base, base, constraints.obliqueGimbalPitchDegrees, SurveyCaptureView.FORWARD_OBLIQUE),
                group(base, base + 180.0, constraints.obliqueGimbalPitchDegrees, SurveyCaptureView.BACKWARD_OBLIQUE),
                group(base + 90.0, base - 90.0, constraints.obliqueGimbalPitchDegrees, SurveyCaptureView.LEFT_OBLIQUE),
                group(base + 90.0, base + 90.0, constraints.obliqueGimbalPitchDegrees, SurveyCaptureView.RIGHT_OBLIQUE),
            ).filter { it.captureView in constraints.enabledCaptureViews }
        }
    }

    private fun generatePasses(
        polygon: List<LocalPoint>,
        headingDegrees: Double,
        spacingMeters: Double,
        footprintWidthMeters: Double,
        boundaryMarginMeters: Double,
    ): List<LocalPass> {
        // Aviation heading is clockwise from north. Local rotation is the
        // mathematical counter-clockwise angle from east.
        val angle = Math.toRadians(90.0 - normalizeHeading(headingDegrees))
        // Rotate the desired flight direction onto local +X; scanlines then vary in Y.
        val rotated = polygon.map { rotate(it, -angle) }
        val coveragePolygon = expandPolygon(rotated, boundaryMarginMeters)
        val minY = coveragePolygon.minOf { it.northMeters }
        val maxY = coveragePolygon.maxOf { it.northMeters }
        val span = maxY - minY
        if (span < 0.2) return emptyList()
        // Pilot 2 keeps a fixed overlap-derived spacing and centres the
        // occupied set of flight lines in the expanded target polygon. Using
        // floor(...)+1 systematically adds an unnecessary outer line. The
        // second bound preserves full edge coverage for low-overlap values
        // outside the Pilot comparison matrix as well.
        val pilotStyleCount = max(1, (span / spacingMeters).roundToInt())
        val coverageSafeCount = if (span <= footprintWidthMeters) 1 else {
            ceil((span - footprintWidthMeters) / spacingMeters).toInt() + 1
        }
        val lineCount = max(pilotStyleCount, coverageSafeCount)
        require(lineCount <= 5_000) { "survey requires too many flight lines" }
        val occupiedSpan = (lineCount - 1) * spacingMeters
        val firstY = (minY + maxY - occupiedSpan) / 2.0
        val passes = mutableListOf<LocalPass>()
        var reverse = false

        repeat(lineCount) { lineIndex ->
            val y = firstY + lineIndex * spacingMeters
            val intersections = horizontalIntersections(coveragePolygon, y)
            if (intersections.size < 2) return@repeat
            val segments = intersections.chunked(2).mapNotNull { pair ->
                val start = pair.getOrNull(0)
                val end = pair.getOrNull(1)
                if (start == null || end == null || end - start < 0.2) null
                else LocalPass(LocalPoint(start, y), LocalPoint(end, y), lineIndex)
            }
            val ordered = if (reverse) segments.asReversed() else segments
            ordered.forEach { segment ->
                val oriented = if (reverse) LocalPass(segment.end, segment.start, lineIndex) else segment
                passes += LocalPass(rotate(oriented.start, angle), rotate(oriented.end, angle), lineIndex)
            }
            if (segments.isNotEmpty()) reverse = !reverse
        }
        return passes
    }

    /**
     * Applies a true polygon buffer in the local metric frame. This is the
     * positive-margin behavior used by DJI mapping missions: the flown/photo
     * footprint covers the selected boundary instead of shrinking inside it.
     */
    private fun expandPolygon(polygon: List<LocalPoint>, marginMeters: Double): List<LocalPoint> {
        if (marginMeters <= 1.0e-9) return polygon
        val openPolygon = if (polygon.size > 3 && polygon.first() == polygon.last()) {
            polygon.dropLast(1)
        } else {
            polygon
        }
        val coordinates = (openPolygon + openPolygon.first()).map {
            Coordinate(it.eastMeters, it.northMeters)
        }.toTypedArray()
        val source = GeometryFactory().createPolygon(coordinates)
        require(source.isValid) { "ROI polygon is not geometrically valid" }
        val parameters = BufferParameters().apply {
            joinStyle = BufferParameters.JOIN_MITRE
            mitreLimit = 5.0
        }
        val buffered = BufferOp.bufferOp(source, marginMeters, parameters)
        require(!buffered.isEmpty) { "ROI boundary expansion produced an empty polygon" }
        val expanded = when (buffered) {
            is Polygon -> buffered
            is MultiPolygon -> (0 until buffered.numGeometries)
                .map { buffered.getGeometryN(it) as Polygon }
                .maxByOrNull { it.area }
                ?: error("ROI boundary expansion produced no polygon")
            else -> error("ROI boundary expansion produced unsupported geometry")
        }
        return expanded.exteriorRing.coordinates.dropLast(1).map {
            LocalPoint(it.x, it.y)
        }
    }

    private fun passFootprint(
        start: LocalPoint,
        end: LocalPoint,
        halfWidthMeters: Double,
        halfLengthMeters: Double,
    ): Polygon? {
        val length = distance(start, end)
        if (length < 1.0e-6) return null
        val alongEast = (end.eastMeters - start.eastMeters) / length
        val alongNorth = (end.northMeters - start.northMeters) / length
        val crossEast = -alongNorth
        val crossNorth = alongEast
        val extendedStart = LocalPoint(
            start.eastMeters - alongEast * halfLengthMeters,
            start.northMeters - alongNorth * halfLengthMeters,
        )
        val extendedEnd = LocalPoint(
            end.eastMeters + alongEast * halfLengthMeters,
            end.northMeters + alongNorth * halfLengthMeters,
        )
        fun offset(point: LocalPoint, sign: Double) = Coordinate(
            point.eastMeters + crossEast * halfWidthMeters * sign,
            point.northMeters + crossNorth * halfWidthMeters * sign,
        )
        val coordinates = arrayOf(
            offset(extendedStart, 1.0),
            offset(extendedEnd, 1.0),
            offset(extendedEnd, -1.0),
            offset(extendedStart, -1.0),
            offset(extendedStart, 1.0),
        )
        return GeometryFactory().createPolygon(coordinates)
    }

    private fun largestPolygon(geometry: Geometry): Polygon = when (geometry) {
        is Polygon -> geometry
        is MultiPolygon -> (0 until geometry.numGeometries)
            .map { geometry.getGeometryN(it) as Polygon }
            .maxByOrNull { it.area }
            ?: error("coverage union produced no polygon")
        else -> (0 until geometry.numGeometries)
            .mapNotNull { geometry.getGeometryN(it) as? Polygon }
            .maxByOrNull { it.area }
            ?: error("coverage union produced unsupported geometry")
    }

    private fun normalizeAndValidateRoi(roi: List<GeoPoint>): List<GeoPoint> {
        val normalized = if (roi.size > 3 && roi.first() == roi.last()) roi.dropLast(1) else roi
        require(normalized.size >= 3) { "ROI requires at least three vertices" }
        require(abs(signedArea(normalized)) > 1.0e-12) { "ROI polygon has zero area" }
        val frame = LocalFrame(centroid(normalized))
        val local = normalized.map(frame::toLocal)
        val coordinates = (local + local.first()).map {
            Coordinate(it.eastMeters, it.northMeters)
        }.toTypedArray()
        val polygon = runCatching { GeometryFactory().createPolygon(coordinates) }
            .getOrElse { throw IllegalArgumentException("ROI polygon is not geometrically valid", it) }
        require(polygon.isValid && polygon.isSimple) { "ROI polygon is not geometrically valid" }
        require(polygon.area >= 1.0) { "ROI area must be at least 1 square meter" }
        return normalized
    }

    private fun horizontalIntersections(polygon: List<LocalPoint>, y: Double): List<Double> {
        val intersections = mutableListOf<Double>()
        polygon.indices.forEach { index ->
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val minY = min(a.northMeters, b.northMeters)
            val maxY = max(a.northMeters, b.northMeters)
            // Half-open edge rule prevents double-counting vertices.
            if (y < minY || y >= maxY || abs(b.northMeters - a.northMeters) < 1.0e-9) return@forEach
            val ratio = (y - a.northMeters) / (b.northMeters - a.northMeters)
            intersections += a.eastMeters + ratio * (b.eastMeters - a.eastMeters)
        }
        return intersections.sorted()
    }

    private fun centroid(points: List<GeoPoint>): GeoPoint = GeoPoint(
        latitude = points.map { it.latitude }.average(),
        longitude = points.map { it.longitude }.average(),
    )

    private fun signedArea(points: List<GeoPoint>): Double = points.indices.sumOf { index ->
        val a = points[index]
        val b = points[(index + 1) % points.size]
        a.longitude * b.latitude - b.longitude * a.latitude
    } / 2.0

    private fun rotate(point: LocalPoint, angle: Double): LocalPoint {
        val c = cos(angle)
        val s = sin(angle)
        return LocalPoint(
            eastMeters = point.eastMeters * c - point.northMeters * s,
            northMeters = point.eastMeters * s + point.northMeters * c,
        )
    }

    private fun headingDegrees(start: LocalPoint, end: LocalPoint): Double = normalizeHeading(
        Math.toDegrees(atan2(end.eastMeters - start.eastMeters, end.northMeters - start.northMeters))
    )

    private fun normalizeHeading(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    private fun distance(a: LocalPoint, b: LocalPoint): Double =
        hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters)
}
