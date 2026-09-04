package edu.playground.djivln.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import java.io.File
import org.junit.Assume.assumeTrue

class SurveyPlannerTest {
    @Test
    fun `phone mission fixture removes kilometer jumps without changing capture geometry`() {
        val path = System.getenv("OPENFLY_SURVEY_FIXTURE").orEmpty()
        assumeTrue("optional phone mission fixture", path.isNotBlank() && File(path).isFile)
        val old = SurveyMissionJson.decode(File(path).readText())
        val replanned = SurveyPlanner.plan(old.name, old.roi, old.cameraProfile, old.constraints,
            takeoffPoint = old.waypoints.first().point)
        System.getenv("OPENFLY_SURVEY_OUTPUT")?.takeIf(String::isNotBlank)?.let {
            File(it).writeText(SurveyMissionJson.encode(replanned))
        }
        fun connectors(value: SurveyMission) = value.surveyPasses().zipWithNext().map { (a, b) ->
            distanceMeters(a.end.point, b.start.point)
        }
        val before = connectors(old)
        val after = connectors(replanned)
        println("PHONE_ROUTE beforeSum=${before.sum()} beforeMax=${before.maxOrNull()} " +
            "afterSum=${after.sum()} afterMax=${after.maxOrNull()} photos=${old.estimatedPhotoCount}/${replanned.estimatedPhotoCount}")
        replanned.surveyPasses().zipWithNext().forEachIndexed { index, (a, b) ->
            val distance = distanceMeters(a.end.point, b.start.point)
            if (distance > 500) println("PHONE_ROUTE_LONG connector=$index distance=$distance " +
                "${a.start.captureView}->${b.start.captureView}")
        }
        assertEquals(old.roi, replanned.roi)
        assertEquals(old.estimatedPhotoCount, replanned.estimatedPhotoCount)
        assertEquals(old.waypoints.map { it.captureView }, replanned.waypoints.map { it.captureView })
        assertTrue(after.sum() < before.sum() * 0.60)
        assertTrue(after.maxOrNull()!! < before.maxOrNull()!! * 0.50)
    }

    @Test
    fun `all start corners preserve serpentine ordering for split concave scanlines`() {
        fun geo(north: Double, east: Double) = GeoPoint(
            31.0 + north / 111_132.0,
            121.0 + east / (111_320.0 * kotlin.math.cos(Math.toRadians(31.0))),
        )
        val concave = listOf(
            geo(0.0, 0.0), geo(0.0, 1_000.0), geo(300.0, 1_000.0),
            geo(300.0, 700.0), geo(100.0, 700.0), geo(100.0, 300.0),
            geo(300.0, 300.0), geo(300.0, 0.0),
        )
        val cornerModes = listOf(SurveyStartPointMode.FIRST_ROUTE_START, SurveyStartPointMode.ROUTE_CORNER_2,
            SurveyStartPointMode.ROUTE_CORNER_3, SurveyStartPointMode.ROUTE_CORNER_4)
        val cornerMissions = cornerModes.map { mode ->
            val mission = SurveyPlanner.plan("split-$mode", concave,
                constraints = SurveyConstraints(altitudeMetersAgl = 80.0, routeHeadingDegrees = 90.0,
                    startPointMode = mode))
            val passes = mission.surveyPasses()
            val connectors = passes.zipWithNext().map { (a, b) -> distanceMeters(a.end.point, b.start.point) }
            assertTrue("$mode made a remote jump: ${connectors.maxOrNull()}", connectors.maxOrNull()!! < 550.0)
            mission
        }
        val reference = geo(-50.0, -50.0)
        val automatic = SurveyPlanner.plan("split-auto", concave,
            constraints = SurveyConstraints(altitudeMetersAgl = 80.0, routeHeadingDegrees = 90.0,
                startPointMode = SurveyStartPointMode.AUTO_NEAREST), takeoffPoint = reference)
        fun routeCost(value: SurveyMission) = distanceMeters(reference, value.waypoints.first().point) +
            value.surveyPasses().zipWithNext().sumOf { (a, b) -> distanceMeters(a.end.point, b.start.point) }
        assertEquals(cornerMissions.minOf(::routeCost), routeCost(automatic), 0.01)
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val n = (b.latitude - a.latitude) * 111_132.0
        val e = (b.longitude - a.longitude) * 111_320.0 *
            kotlin.math.cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return kotlin.math.hypot(n, e)
    }

    @Test
    fun `fixed nadir heading survives alternating passes and every start corner`() {
        for (collection in SurveyCollectionMode.values()) {
            for (start in SurveyStartPointMode.values()) {
                val constraints = SurveyConstraints(
                    altitudeMetersAgl = 30.0,
                    routeHeadingDegrees = 37.0,
                    collectionMode = collection,
                    enabledCaptureViews = setOf(SurveyCaptureView.NADIR),
                    startPointMode = start,
                )
                fun planned(mode: SurveyObliqueHeadingMode) = SurveyPlanner.plan(
                    name = "nadir-heading-regression", roi = rectangularRoi,
                    constraints = constraints.copy(obliqueHeadingMode = mode),
                    takeoffPoint = rectangularRoi.last(),
                )
                val tracking = planned(SurveyObliqueHeadingMode.TRACK_ROUTE)
                val fixed = planned(SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION)
                val expectedHeadings = if (collection == SurveyCollectionMode.CROSSHATCH_NADIR) {
                    setOf(37, 127)
                } else setOf(37)
                assertEquals("$collection / $start", expectedHeadings,
                    fixed.waypoints.map { it.headingDegrees.roundToInt() }.toSet())
                // The switch changes orientation only, never positions, ordering or pitch.
                assertEquals(tracking.waypoints.map { it.point }, fixed.waypoints.map { it.point })
                assertEquals(tracking.waypoints.map { it.gimbalPitchDegrees }, fixed.waypoints.map { it.gimbalPitchDegrees })
                val trackingHeadings = tracking.waypoints.chunked(2).map { it.first().headingDegrees }
                assertTrue("tracking must still turn around: $collection / $start",
                    trackingHeadings.zipWithNext().any { (a, b) ->
                        kotlin.math.abs(((b - a + 540.0) % 360.0) - 180.0) > 170.0
                    })
            }
        }
    }

    private val rectangularRoi = listOf(
        GeoPoint(31.00000, 121.00000),
        GeoPoint(31.00000, 121.00100),
        GeoPoint(31.00060, 121.00100),
        GeoPoint(31.00060, 121.00000),
    )

    @Test
    fun `planned eta includes yaw gimbal and point capture budgets`() {
        val base = SurveyPlanner.plan("eta-actions", rectangularRoi)
        val start = base.waypoints.first().copy(
            headingDegrees = 0.0, gimbalPitchDegrees = -90.0,
            captureAction = CaptureAction.NONE,
        )
        val end = start.copy(
            headingDegrees = 90.0, gimbalPitchDegrees = -45.0,
            kind = SurveyWaypointKind.CAPTURE_POINT,
            captureAction = CaptureAction.CAPTURE_ON_REACH,
        )
        assertEquals(10.0, SurveyPlanner.estimateRouteSeconds(
            listOf(start, end), base.constraints), 0.0)
    }

    @Test
    fun `point capture sortie keeps its photo and camera budget`() {
        val base = SurveyPlanner.plan("point-sortie", rectangularRoi)
        val waypoint = base.waypoints.first().copy(
            kind = SurveyWaypointKind.CAPTURE_POINT,
            captureAction = CaptureAction.CAPTURE_ON_REACH,
            captureIntervalMeters = null,
        )
        val mission = base.copy(waypoints = listOf(waypoint), estimatedPhotoCount = 1,
            estimatedFlightSeconds = SurveyEtaPolicy.CAPTURE_ON_REACH_SECONDS)
        val sortie = SurveyPlanner.planSorties(mission).single()
        assertEquals(1, sortie.estimatedPhotoCount)
        assertEquals(SurveyEtaPolicy.CAPTURE_ON_REACH_SECONDS,
            sortie.estimatedFlightSeconds, 0.0)
    }

    @Test
    fun `mini 2 coverage derives positive spacing and gsd`() {
        val coverage = SurveyPlanner.coverage(
            CameraProfile.DJI_MINI_2,
            SurveyConstraints(altitudeMetersAgl = 60.0),
        )

        assertTrue(coverage.footprintWidthMeters > coverage.footprintLengthMeters)
        assertTrue(coverage.lineSpacingMeters > 1.0)
        assertTrue(coverage.captureIntervalMeters > 1.0)
        assertTrue(coverage.groundSampleDistanceCentimeters in 1.0..5.0)
        assertEquals(
            60.0,
            SurveyPlanner.altitudeForGroundSampleDistance(
                CameraProfile.DJI_MINI_2,
                coverage.groundSampleDistanceCentimeters,
            ),
            1.0e-9,
        )
    }

    @Test
    fun `distance capture exposes camera limited maximum speed`() {
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 30.0,
            forwardOverlap = 0.8,
            sideOverlap = 0.7,
            speedMetersPerSecond = 10.0,
        )

        val limit = SurveyPlanner.speedLimit(CameraProfile.DJI_MINI_2, constraints)

        assertEquals(10.0, limit.hardMaximumMetersPerSecond, 0.0)
        assertTrue(limit.cameraLimited)
        assertTrue(limit.effectiveMaximumMetersPerSecond < 10.0)
        assertTrue(limit.exceeded)
    }

    @Test
    fun `mini 4 pro distance capture uses measured two point five second floor`() {
        val camera = DjiCameraProfileCatalog.resolve("DJI_MINI_4_PRO").profile
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 40.0,
            forwardOverlap = 0.8,
            speedMetersPerSecond = 6.0,
        )

        val coverage = SurveyPlanner.coverage(camera, constraints)
        val feasibility = SurveyPlanner.captureFeasibility(camera, constraints)
        val limit = SurveyPlanner.speedLimit(camera, constraints)

        assertEquals(2.5, feasibility.minimumIntervalSeconds, 0.0)
        assertEquals(coverage.captureIntervalMeters / 2.5, limit.effectiveMaximumMetersPerSecond, 1.0e-9)
        assertTrue(limit.effectiveMaximumMetersPerSecond in 3.3..3.4)
        assertTrue(limit.exceeded)
    }

    @Test
    fun `legacy mini 2 profile cannot bypass calibrated capture interval`() {
        val legacyProfile = CameraProfile.DJI_MINI_2.copy(minimumCaptureIntervalSeconds = 1.0)
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 30.0,
            speedMetersPerSecond = 5.0,
        )

        val feasibility = SurveyPlanner.captureFeasibility(legacyProfile, constraints)

        assertEquals(2.0, feasibility.minimumIntervalSeconds, 0.0)
        assertEquals(
            SurveyPlanner.captureFeasibility(CameraProfile.DJI_MINI_2, constraints),
            feasibility,
        )
    }

    @Test
    fun `timed capture uses virtual stick hard speed limit`() {
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 30.0,
            speedMetersPerSecond = 5.0,
            captureTriggerMode = SurveyCaptureTriggerMode.TIME,
            timedCaptureIntervalSeconds = 1.0,
        )

        val limit = SurveyPlanner.speedLimit(CameraProfile.DJI_MINI_2, constraints)

        assertEquals(10.0, limit.effectiveMaximumMetersPerSecond, 0.0)
        assertTrue(!limit.cameraLimited)
        assertTrue(!limit.exceeded)
    }

    @Test
    fun `oblique capture feasibility uses the independently configured speed`() {
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 40.0,
            speedMetersPerSecond = 2.0,
            obliqueSpeedMetersPerSecond = 6.0,
            collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
        )

        val feasibility = SurveyPlanner.captureFeasibility(
            CameraProfile.DJI_MINI_2,
            constraints,
            oblique = true,
        )
        val coverage = SurveyPlanner.obliqueCoverage(CameraProfile.DJI_MINI_2, constraints)

        assertEquals(coverage.captureIntervalMeters / 6.0, feasibility.requestedIntervalSeconds, 1.0e-9)
    }

    @Test
    fun `grid mission alternates pass direction and estimates captures`() {
        val mission = SurveyPlanner.plan(
            name = "rectangle-grid",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 50.0,
                routeHeadingDegrees = 90.0,
            ),
        )

        assertTrue(mission.waypoints.size >= 4)
        assertEquals(0, mission.waypoints.size % 2)
        assertEquals(CaptureAction.START_DISTANCE_INTERVAL, mission.waypoints.first().captureAction)
        assertEquals(CaptureAction.STOP_DISTANCE_INTERVAL, mission.waypoints[1].captureAction)
        assertTrue(mission.estimatedPathMeters > 100.0)
        assertTrue(mission.estimatedPhotoCount > 4)

        val firstHeading = mission.waypoints[0].headingDegrees
        val secondHeading = mission.waypoints[2].headingDegrees
        val difference = ((secondHeading - firstHeading + 540.0) % 360.0) - 180.0
        assertTrue(kotlin.math.abs(difference) > 170.0)
    }

    @Test
    fun `legacy crosshatch adds orthogonal nadir passes`() {
        val grid = SurveyPlanner.plan(
            name = "grid",
            roi = rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0, crosshatch = false),
        )
        val crosshatch = SurveyPlanner.plan(
            name = "crosshatch",
            roi = rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0, crosshatch = true),
        )

        assertTrue(crosshatch.waypoints.size > grid.waypoints.size)
        assertTrue(crosshatch.estimatedPathMeters > grid.estimatedPathMeters)
        assertTrue(crosshatch.estimatedPhotoCount > grid.estimatedPhotoCount)
    }

    @Test
    fun `five direction oblique creates nadir and four distinct oblique route groups`() {
        val ortho = SurveyPlanner.plan(
            name = "ortho",
            roi = rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0),
        )
        val oblique = SurveyPlanner.plan(
            name = "five-direction",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 50.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
                obliqueGimbalPitchDegrees = -45.0,
            ),
        )

        assertEquals(STANDARD_SURVEY_CAPTURE_VIEWS, oblique.waypoints.map { it.captureView }.toSet())
        assertEquals(setOf(-90.0, -45.0), oblique.waypoints.map { it.gimbalPitchDegrees }.toSet())
        assertTrue(oblique.waypoints.size > ortho.waypoints.size)
        assertTrue(oblique.estimatedPathMeters > ortho.estimatedPathMeters)
        assertTrue(oblique.estimatedPhotoCount > ortho.estimatedPhotoCount)
        val nadirWaypoints = oblique.waypoints.filter { it.captureView == SurveyCaptureView.NADIR }
        assertEquals(ortho.waypoints.size, nadirWaypoints.size)
        assertTrue(nadirWaypoints.all { it.gimbalPitchDegrees == -90.0 })
        val obliqueIntervals = oblique.waypoints
            .filter { it.captureView != SurveyCaptureView.NADIR }
            .mapNotNull { it.captureIntervalMeters }
        assertTrue(obliqueIntervals.isNotEmpty())
        assertTrue(obliqueIntervals.all { it != ortho.waypoints.first().captureIntervalMeters })
    }

    @Test
    fun `five direction can generate only selected route groups`() {
        val selected = setOf(
            SurveyCaptureView.FORWARD_OBLIQUE,
            SurveyCaptureView.RIGHT_OBLIQUE,
        )
        val mission = SurveyPlanner.plan(
            name = "selected-oblique",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 50.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
                obliqueGimbalPitchDegrees = -45.0,
                enabledCaptureViews = selected,
            ),
        )

        assertEquals(selected, mission.waypoints.map { it.captureView }.toSet())
        assertTrue(mission.waypoints.none { it.captureView == SurveyCaptureView.NADIR })
    }

    @Test
    fun `safe oblique heading follows each route direction while fixed mode may reverse`() {
        fun plan(mode: SurveyObliqueHeadingMode) = SurveyPlanner.plan(
            name = mode.name,
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 50.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
                obliqueGimbalPitchDegrees = -45.0,
                enabledCaptureViews = setOf(SurveyCaptureView.FORWARD_OBLIQUE),
                obliqueHeadingMode = mode,
            ),
        )

        val routeTracking = plan(SurveyObliqueHeadingMode.TRACK_ROUTE)
        val fixedDirection = plan(SurveyObliqueHeadingMode.FIXED_CAPTURE_DIRECTION)
        val routeHeadings = routeTracking.waypoints.chunked(2).map { it.first().headingDegrees }
        val fixedHeadings = fixedDirection.waypoints.chunked(2).map { it.first().headingDegrees }

        assertTrue(routeHeadings.zipWithNext().any { (first, second) ->
            kotlin.math.abs(((second - first + 540.0) % 360.0) - 180.0) > 170.0
        })
        assertEquals(1, fixedHeadings.map { it.roundToInt() }.toSet().size)
    }

    @Test
    fun `target surface offset changes takeoff relative waypoint height but not GSD`() {
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 50.0,
            altitudeMode = SurveyAltitudeMode.ABOVE_TARGET_SURFACE,
            targetSurfaceToTakeoffMeters = 15.0,
        )
        val mission = SurveyPlanner.plan("raised-target", rectangularRoi, constraints = constraints)
        val coverage = SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, constraints)

        assertTrue(mission.waypoints.all { it.point.altitudeMeters == 65.0 })
        assertEquals(
            SurveyPlanner.coverage(
                CameraProfile.DJI_MINI_2,
                SurveyConstraints(altitudeMetersAgl = 50.0),
            ).groundSampleDistanceCentimeters,
            coverage.groundSampleDistanceCentimeters,
            0.0,
        )
    }

    @Test
    fun `oblique overlap settings affect oblique grid independently`() {
        val baseline = SurveyConstraints(
            altitudeMetersAgl = 50.0,
            collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
            obliqueForwardOverlap = 0.80,
            obliqueSideOverlap = 0.70,
        )
        val lowerOverlap = baseline.copy(
            obliqueForwardOverlap = 0.60,
            obliqueSideOverlap = 0.50,
        )
        val baseCoverage = SurveyPlanner.obliqueCoverage(CameraProfile.DJI_MINI_2, baseline)
        val lowerCoverage = SurveyPlanner.obliqueCoverage(CameraProfile.DJI_MINI_2, lowerOverlap)

        assertEquals(baseCoverage.captureIntervalMeters * 2.0, lowerCoverage.captureIntervalMeters, 1.0e-9)
        assertTrue(lowerCoverage.lineSpacingMeters > baseCoverage.lineSpacingMeters)
        assertEquals(
            SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, baseline),
            SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, lowerOverlap),
        )
    }

    @Test
    fun `mission statistics split photos by view and estimate storage and sorties`() {
        val mission = SurveyPlanner.plan(
            "statistics",
            rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                speedMetersPerSecond = 2.0,
                collectionMode = SurveyCollectionMode.OBLIQUE_FIVE_DIRECTION,
            ),
        )
        val statistics = SurveyPlanner.statistics(
            mission,
            assumedJpegMegabytes = 4.0,
            assumedUsableSortieSeconds = 60.0,
            launchPoint = GeoPoint(30.9998, 120.9998, 0.0),
        )

        assertEquals(STANDARD_SURVEY_CAPTURE_VIEWS, statistics.photoCountByView.keys)
        assertEquals(mission.estimatedPhotoCount, statistics.photoCountByView.values.sum())
        assertEquals(mission.estimatedPhotoCount * 4.0, statistics.estimatedStorageMegabytes, 0.0)
        assertTrue(statistics.estimatedSorties >= 2)
        assertEquals(statistics.estimatedSorties, statistics.sorties.size)
        assertTrue(statistics.sorties.all {
            it.firstWaypointIndex % 2 == 0 && it.lastWaypointIndex % 2 == 1
        })
        assertEquals(mission.waypoints.lastIndex, statistics.sorties.last().lastWaypointIndex)
        assertTrue(statistics.operationalPathMeters > mission.estimatedPathMeters)
        assertTrue(statistics.operationalFlightSeconds > mission.estimatedFlightSeconds)
        assertTrue(statistics.transitAndCompletionSeconds > 0.0)
        assertTrue(statistics.operationalReferenceAvailable)
    }

    @Test
    fun `sortie planner never divides a photo pass`() {
        val mission = SurveyPlanner.plan(
            "sorties",
            rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 30.0, speedMetersPerSecond = 2.0),
        )
        val sorties = SurveyPlanner.planSorties(mission, usableSortieSeconds = 30.0)

        assertTrue(sorties.size > 1)
        sorties.zipWithNext().forEach { (before, after) ->
            assertEquals(before.lastWaypointIndex + 1, after.firstWaypointIndex)
        }
        assertEquals(mission.estimatedPhotoCount, sorties.sumOf { it.estimatedPhotoCount })
    }

    @Test
    fun `forward and side overlap affect independent planning dimensions`() {
        val baselineConstraints = SurveyConstraints(
            altitudeMetersAgl = 50.0,
            routeHeadingDegrees = 90.0,
            forwardOverlap = 0.80,
            sideOverlap = 0.70,
        )
        val lowerForwardConstraints = baselineConstraints.copy(forwardOverlap = 0.70)
        val lowerSideConstraints = baselineConstraints.copy(sideOverlap = 0.60)

        val baselineCoverage = SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, baselineConstraints)
        val lowerForwardCoverage = SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, lowerForwardConstraints)
        val lowerSideCoverage = SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, lowerSideConstraints)

        assertEquals(baselineCoverage.lineSpacingMeters, lowerForwardCoverage.lineSpacingMeters, 1.0e-9)
        assertEquals(
            baselineCoverage.captureIntervalMeters * 1.5,
            lowerForwardCoverage.captureIntervalMeters,
            1.0e-9,
        )
        assertEquals(baselineCoverage.captureIntervalMeters, lowerSideCoverage.captureIntervalMeters, 1.0e-9)
        assertEquals(
            baselineCoverage.lineSpacingMeters * (4.0 / 3.0),
            lowerSideCoverage.lineSpacingMeters,
            1.0e-9,
        )

        val largeRoi = listOf(
            GeoPoint(31.0000, 121.0000),
            GeoPoint(31.0000, 121.0020),
            GeoPoint(31.0015, 121.0020),
            GeoPoint(31.0015, 121.0000),
        )
        val baseline = SurveyPlanner.plan("baseline", largeRoi, constraints = baselineConstraints)
        val lowerForward = SurveyPlanner.plan("lower-forward", largeRoi, constraints = lowerForwardConstraints)
        val lowerSide = SurveyPlanner.plan("lower-side", largeRoi, constraints = lowerSideConstraints)

        assertEquals(baseline.waypoints, lowerForward.waypoints.mapIndexed { index, waypoint ->
            waypoint.copy(captureIntervalMeters = baseline.waypoints[index].captureIntervalMeters)
        })
        assertEquals(baseline.estimatedPathMeters, lowerForward.estimatedPathMeters, 1.0e-6)
        assertTrue(lowerForward.estimatedPhotoCount < baseline.estimatedPhotoCount)
        assertTrue(lowerSide.waypoints.size < baseline.waypoints.size)
        assertTrue(lowerSide.estimatedPathMeters < baseline.estimatedPathMeters)
    }

    @Test
    fun `speed changes time trigger conversion but not planned capture distance`() {
        val slow = SurveyPlanner.coverage(
            CameraProfile.DJI_MINI_2,
            SurveyConstraints(altitudeMetersAgl = 60.0, speedMetersPerSecond = 2.0),
        )
        val fast = SurveyPlanner.coverage(
            CameraProfile.DJI_MINI_2,
            SurveyConstraints(altitudeMetersAgl = 60.0, speedMetersPerSecond = 5.0),
        )

        assertEquals(slow.captureIntervalMeters, fast.captureIntervalMeters, 0.0)
        assertEquals(slow.lineSpacingMeters, fast.lineSpacingMeters, 0.0)
        assertTrue(SurveyPlanner.captureFeasibility(
            CameraProfile.DJI_MINI_2,
            SurveyConstraints(altitudeMetersAgl = 60.0, speedMetersPerSecond = 2.0),
        ).feasible)
        val infeasible = SurveyPlanner.captureFeasibility(
            CameraProfile.DJI_MINI_2,
            SurveyConstraints(altitudeMetersAgl = 10.0, speedMetersPerSecond = 5.0),
        )
        assertTrue(!infeasible.feasible)
        assertTrue(infeasible.maximumFeasibleSpeedMetersPerSecond < 5.0)
    }

    @Test
    fun `positive boundary margin expands coverage outside the selected ROI`() {
        val noMargin = SurveyPlanner.plan(
            name = "no-margin",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                routeHeadingDegrees = 90.0,
                boundaryMarginMeters = 0.0,
            ),
        )
        val expanded = SurveyPlanner.plan(
            name = "expanded",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                routeHeadingDegrees = 90.0,
                boundaryMarginMeters = 10.0,
            ),
        )

        assertTrue(expanded.waypoints.size >= noMargin.waypoints.size)
        assertTrue(expanded.estimatedPathMeters > noMargin.estimatedPathMeters)
        assertTrue(expanded.waypoints.minOf { it.point.longitude } < 121.00000)
        assertTrue(expanded.waypoints.maxOf { it.point.longitude } > 121.00100)
        val selectedTarget = SurveyPlanner.targetArea(noMargin)
        val expandedTarget = SurveyPlanner.targetArea(expanded)
        assertTrue(expandedTarget.areaSquareMeters > selectedTarget.areaSquareMeters)
        assertTrue(expandedTarget.boundary.minOf { it.longitude } < 121.00000)
        assertTrue(expandedTarget.boundary.maxOf { it.longitude } > 121.00100)
    }

    @Test
    fun `flight line count follows centered fixed spacing instead of adding an outer line`() {
        val constraints = SurveyConstraints(
            altitudeMetersAgl = 30.0,
            routeHeadingDegrees = 0.0,
            boundaryMarginMeters = 0.0,
        )
        val mission = SurveyPlanner.plan(
            name = "centered-spacing",
            roi = rectangularRoi,
            constraints = constraints,
        )
        val coverage = SurveyPlanner.coverage(CameraProfile.DJI_MINI_2, constraints)
        val eastWestSpanMeters = 0.001 * 111_320.0 * kotlin.math.cos(Math.toRadians(31.0003))
        val expectedPasses = (eastWestSpanMeters / coverage.lineSpacingMeters).roundToInt()

        assertEquals(expectedPasses, mission.waypoints.size / 2)
    }

    @Test
    fun `reported image footprint extends past all ROI edges even with zero margin`() {
        val mission = SurveyPlanner.plan(
            name = "footprint-envelope",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                routeHeadingDegrees = 90.0,
                boundaryMarginMeters = 0.0,
            ),
        )
        val footprint = SurveyPlanner.groundCoverage(mission)

        assertTrue(footprint.boundary.minOf { it.latitude } < rectangularRoi.minOf { it.latitude })
        assertTrue(footprint.boundary.maxOf { it.latitude } > rectangularRoi.maxOf { it.latitude })
        assertTrue(footprint.boundary.minOf { it.longitude } < rectangularRoi.minOf { it.longitude })
        assertTrue(footprint.boundary.maxOf { it.longitude } > rectangularRoi.maxOf { it.longitude })
        assertTrue(footprint.areaSquareMeters > 7_000.0)
    }

    @Test
    fun `planner rejects self intersecting ROI even when outward margin is zero`() {
        val selfIntersecting = listOf(
            GeoPoint(31.00000, 121.00000),
            GeoPoint(31.00060, 121.00100),
            GeoPoint(31.00060, 121.00000),
            GeoPoint(31.00000, 121.00100),
            GeoPoint(31.00030, 121.00120),
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SurveyPlanner.plan("invalid-roi", selfIntersecting)
        }
    }

    @Test
    fun `planner normalizes a GeoJSON style closed ROI ring`() {
        val mission = SurveyPlanner.plan("closed-ring", rectangularRoi + rectangularRoi.first())

        assertEquals(rectangularRoi, mission.roi)
    }

    @Test
    fun `mission model rejects incomplete imported survey passes`() {
        val mission = SurveyPlanner.plan("valid", rectangularRoi)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            mission.copy(waypoints = mission.waypoints.dropLast(1))
        }
    }

    @Test
    fun `coverage safety guard protects edges at zero side overlap`() {
        val mission = SurveyPlanner.plan(
            name = "zero-side-overlap",
            roi = rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                routeHeadingDegrees = 0.0,
                sideOverlap = 0.0,
            ),
        )
        val footprint = SurveyPlanner.groundCoverage(mission)

        assertTrue(footprint.boundary.minOf { it.longitude } <= rectangularRoi.minOf { it.longitude })
        assertTrue(footprint.boundary.maxOf { it.longitude } >= rectangularRoi.maxOf { it.longitude })
    }

    @Test
    fun `route heading follows aviation convention`() {
        val northSouth = SurveyPlanner.plan(
            name = "north-south",
            roi = rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0, routeHeadingDegrees = 0.0),
        )
        val eastWest = SurveyPlanner.plan(
            name = "east-west",
            roi = rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0, routeHeadingDegrees = 90.0),
        )

        assertHeadingAxis(northSouth.waypoints.first().headingDegrees, 0.0)
        assertHeadingAxis(eastWest.waypoints.first().headingDegrees, 90.0)
    }

    @Test
    fun `suggested route heading follows long side of fitted rectangle`() {
        val center = GeoPoint(31.0, 121.0)
        val heading = 32.0
        val along = 120.0
        val cross = 45.0
        val radians = Math.toRadians(heading)
        val alongEast = kotlin.math.sin(radians)
        val alongNorth = kotlin.math.cos(radians)
        val crossEast = kotlin.math.cos(radians)
        val crossNorth = -kotlin.math.sin(radians)
        fun corner(alongSign: Double, crossSign: Double): GeoPoint {
            val east = alongEast * along * 0.5 * alongSign +
                crossEast * cross * 0.5 * crossSign
            val north = alongNorth * along * 0.5 * alongSign +
                crossNorth * cross * 0.5 * crossSign
            return GeoPoint(
                center.latitude + north / 111_132.0,
                center.longitude + east / (111_320.0 * kotlin.math.cos(Math.toRadians(center.latitude))),
            )
        }
        val rotatedRectangle = listOf(
            corner(-1.0, -1.0), corner(1.0, -1.0),
            corner(1.0, 1.0), corner(-1.0, 1.0),
        )

        assertHeadingAxis(SurveyPlanner.suggestedRouteHeading(rotatedRectangle), heading)
    }

    @Test
    fun `auto nearest start chooses the route corner closest to takeoff`() {
        val southWest = GeoPoint(30.9998, 120.9998)
        val northEast = GeoPoint(31.0008, 121.0012)
        val fixed = SurveyPlanner.plan(
            "fixed-start",
            rectangularRoi,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 50.0,
                startPointMode = SurveyStartPointMode.FIRST_ROUTE_START,
            ),
            takeoffPoint = northEast,
        )
        val nearSouthWest = SurveyPlanner.plan(
            "near-sw",
            rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0),
            takeoffPoint = southWest,
        )
        val nearNorthEast = SurveyPlanner.plan(
            "near-ne",
            rectangularRoi,
            constraints = SurveyConstraints(altitudeMetersAgl = 50.0),
            takeoffPoint = northEast,
        )

        assertTrue(
            approximateDistanceMeters(southWest, nearSouthWest.waypoints.first().point) <
                approximateDistanceMeters(southWest, fixed.waypoints.first().point),
        )
        assertTrue(nearNorthEast.waypoints.first().point.latitude > nearSouthWest.waypoints.first().point.latitude)
        assertTrue(nearNorthEast.waypoints.first().point.longitude > nearSouthWest.waypoints.first().point.longitude)
    }

    @Test
    fun `four explicit start choices select the four route corners`() {
        val modes = listOf(
            SurveyStartPointMode.FIRST_ROUTE_START,
            SurveyStartPointMode.ROUTE_CORNER_2,
            SurveyStartPointMode.ROUTE_CORNER_3,
            SurveyStartPointMode.ROUTE_CORNER_4,
        )
        val starts = modes.map { mode ->
            SurveyPlanner.plan(
                "corner-$mode",
                rectangularRoi,
                constraints = SurveyConstraints(
                    altitudeMetersAgl = 30.0,
                    routeHeadingDegrees = 90.0,
                    startPointMode = mode,
                ),
            ).waypoints.first().point
        }

        assertEquals(4, starts.map { it.latitude to it.longitude }.toSet().size)
    }

    @Test
    fun `concave polygon creates only in-polygon scan segments`() {
        val concave = listOf(
            GeoPoint(31.0000, 121.0000),
            GeoPoint(31.0000, 121.0010),
            GeoPoint(31.0003, 121.0010),
            GeoPoint(31.0003, 121.0004),
            GeoPoint(31.0008, 121.0004),
            GeoPoint(31.0008, 121.0000),
        )

        val mission = SurveyPlanner.plan(
            name = "concave",
            roi = concave,
            constraints = SurveyConstraints(altitudeMetersAgl = 30.0, routeHeadingDegrees = 90.0),
        )

        assertTrue(mission.waypoints.isNotEmpty())
        mission.waypoints.forEach {
            assertTrue(it.point.latitude in 31.0000..31.0008)
            assertTrue(it.point.longitude in 121.0000..121.0010)
        }
    }

    @Test
    fun `concave polygon supports outward boundary buffering`() {
        val concave = listOf(
            GeoPoint(31.0000, 121.0000),
            GeoPoint(31.0000, 121.0010),
            GeoPoint(31.0003, 121.0010),
            GeoPoint(31.0003, 121.0004),
            GeoPoint(31.0008, 121.0004),
            GeoPoint(31.0008, 121.0000),
        )

        val expanded = SurveyPlanner.plan(
            name = "concave-expanded",
            roi = concave,
            constraints = SurveyConstraints(
                altitudeMetersAgl = 30.0,
                routeHeadingDegrees = 90.0,
                boundaryMarginMeters = 5.0,
            ),
        )

        assertTrue(expanded.waypoints.isNotEmpty())
        assertTrue(expanded.waypoints.minOf { it.point.longitude } < 121.0000)
        assertTrue(expanded.waypoints.maxOf { it.point.longitude } > 121.0010)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `degenerate ROI is rejected`() {
        SurveyPlanner.plan(
            name = "bad",
            roi = listOf(
                GeoPoint(31.0, 121.0),
                GeoPoint(31.0, 121.0001),
                GeoPoint(31.0, 121.0002),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbounded map selection is rejected before scanline allocation`() {
        SurveyPlanner.plan(
            name = "world-sized",
            roi = listOf(
                GeoPoint(30.0, 120.0),
                GeoPoint(30.0, 121.0),
                GeoPoint(31.0, 121.0),
                GeoPoint(31.0, 120.0),
            ),
        )
    }

    private fun assertHeadingAxis(actual: Double, expectedAxis: Double) {
        val forwardDifference = kotlin.math.abs(((actual - expectedAxis + 540.0) % 360.0) - 180.0)
        val reverseDifference = kotlin.math.abs(((actual - expectedAxis - 180.0 + 540.0) % 360.0) - 180.0)
        assertTrue(minOf(forwardDifference, reverseDifference) < 1.0)
    }

    private fun approximateDistanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val north = (a.latitude - b.latitude) * 111_132.0
        val east = (a.longitude - b.longitude) * 111_320.0 *
            kotlin.math.cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return kotlin.math.hypot(north, east)
    }
}
