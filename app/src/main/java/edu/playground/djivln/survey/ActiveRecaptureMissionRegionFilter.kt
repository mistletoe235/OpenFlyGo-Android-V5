package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

object ActiveRecaptureMissionRegionFilter {
    fun selectGroups(
        mission: SurveyMission,
        selectedGroupIds: Set<String>,
        context: Context? = null,
    ): SurveyMission {
        val groups = ActiveRecaptureMissionGroupCatalog.groups(mission)
        val availableGroupIds = groups.mapTo(hashSetOf()) { it.groupId }
        require(selectedGroupIds.isNotEmpty()) { "at least one active recapture group is required" }
        require(selectedGroupIds.all { it in availableGroupIds }) {
            "active recapture selection contains an unknown group"
        }
        if (selectedGroupIds == availableGroupIds) return mission
        val selectedGroups = groups.filter { it.groupId in selectedGroupIds }
        val selectedRegionIds = selectedGroups.flatMapTo(linkedSetOf()) { it.regionIds }
        return select(mission, selectedRegionIds, context).copy(
            name = context?.getString(
                R.string.mission_name_group_selection,
                mission.name,
                selectedGroups.size,
                groups.size,
            ) ?: "${mission.name} · ${selectedGroups.size}/${groups.size} groups",
        )
    }

    fun select(
        mission: SurveyMission,
        selectedRegionIds: Set<String>,
        context: Context? = null,
    ): SurveyMission {
        val metadata = requireNotNull(mission.activeMapping) {
            "only active recapture missions can be filtered by region"
        }
        val availableRegionIds = metadata.regions.map { it.regionId }.toSet()
        require(selectedRegionIds.isNotEmpty()) { "at least one active recapture region is required" }
        require(selectedRegionIds.all { it in availableRegionIds }) {
            "active recapture selection contains an unknown region"
        }
        if (selectedRegionIds == availableRegionIds) return mission

        val sourcePasses = mission.surveyPasses()
        val metadataByPassIndex = metadata.passes.associateBy { it.passIndex }
        val selectedPassPositions = sourcePasses.indices.filter { position ->
            val passMetadata = requireNotNull(metadataByPassIndex[sourcePasses[position].start.passIndex]) {
                "active recapture pass metadata is missing"
            }
            passMetadata.regionId in selectedRegionIds && passMetadata.captureRole != "NONE"
        }
        require(selectedPassPositions.isNotEmpty()) { "selected regions do not contain executable passes" }

        val firstPosition = selectedPassPositions.first()
        val lastPosition = selectedPassPositions.last()
        val filteredWaypoints = mutableListOf<SurveyWaypoint>()
        val filteredPassMetadata = mutableListOf<ActiveMappingPassMetadata>()
        val selectedPassIndexMap = mutableMapOf<Int, Int>()
        var nextPassIndex = 0
        for (position in firstPosition..lastPosition) {
            val sourcePass = sourcePasses[position]
            val sourceMetadata = requireNotNull(metadataByPassIndex[sourcePass.start.passIndex]) {
                "active recapture pass metadata is missing"
            }
            val selected = sourceMetadata.regionId in selectedRegionIds && sourceMetadata.captureRole != "NONE"
            val outputPassIndex = nextPassIndex++
            if (selected) {
                filteredWaypoints += sourcePass.waypoints.map { it.copy(passIndex = outputPassIndex) }
                filteredPassMetadata += sourceMetadata.copy(passIndex = outputPassIndex)
                selectedPassIndexMap[sourcePass.start.passIndex] = outputPassIndex
            } else {
                filteredWaypoints += sourcePass.waypoints.map {
                    it.copy(
                        kind = SurveyWaypointKind.TRANSIT,
                        captureAction = CaptureAction.NONE,
                        captureIntervalMeters = null,
                        passIndex = outputPassIndex,
                    )
                }
                filteredPassMetadata += sourceMetadata.copy(
                    passIndex = outputPassIndex,
                    regionId = "SELECTION_TRANSIT",
                    role = "SELECTION_TRANSIT",
                    captureRole = "NONE",
                    requiredForReconstructionBridge = false,
                )
            }
        }

        val selectedRegions = metadata.regions
            .filter { it.regionId in selectedRegionIds }
            .map { region ->
                region.copy(
                    passIndices = region.passIndices.mapNotNull(selectedPassIndexMap::get),
                )
            }
        val filteredPasses = groupPasses(filteredWaypoints)
        val photoCount = filteredPasses.sumOf(::captureCount)
        val surveyPhotoCount = selectedRegions.sumOf { it.suggestedSurveyPhotos }
            .coerceAtMost(photoCount)
        val pathMeters = filteredWaypoints.zipWithNext().sumOf { (start, end) ->
            distanceMeters(start.point, end.point)
        }
        val selectionKey = selectedRegions.sortedBy { it.priority }.joinToString(",") { it.regionId }
        val filtered = mission.copy(
            id = UUID.nameUUIDFromBytes("${mission.id}|$selectionKey".toByteArray(StandardCharsets.UTF_8)).toString(),
            name = context?.getString(
                R.string.mission_name_group_selection,
                mission.name,
                selectedRegions.size,
                metadata.regions.size,
            ) ?: "${mission.name} · ${selectedRegions.size}/${metadata.regions.size} groups",
            roi = boundingRoi(filteredWaypoints.map { it.point }),
            waypoints = filteredWaypoints,
            estimatedPathMeters = pathMeters,
            estimatedPhotoCount = photoCount,
            estimatedFlightSeconds = SurveyPlanner.estimateRouteSeconds(filteredWaypoints, mission.constraints),
            activeMapping = metadata.copy(
                selectionMethod = "${metadata.selectionMethod}:android_region_filter",
                sourceCaptureCount = photoCount,
                surveyCaptureCount = surveyPhotoCount,
                bridgeCaptureCount = photoCount - surveyPhotoCount,
                sourceEstimatedRouteDistanceMeters = pathMeters,
                regions = selectedRegions,
                passes = filteredPassMetadata,
            ),
        )
        ActiveRecaptureMissionValidator.validate(filtered)
        return filtered
    }

    private fun groupPasses(waypoints: List<SurveyWaypoint>): List<List<SurveyWaypoint>> {
        val result = mutableListOf<List<SurveyWaypoint>>()
        var first = 0
        while (first < waypoints.size) {
            val passIndex = waypoints[first].passIndex
            var last = first
            while (last + 1 < waypoints.size && waypoints[last + 1].passIndex == passIndex) last++
            result += waypoints.subList(first, last + 1)
            first = last + 1
        }
        return result
    }

    private fun captureCount(pass: List<SurveyWaypoint>): Int {
        val start = pass.first()
        if (start.captureAction == CaptureAction.CAPTURE_ON_REACH) return 1
        if (start.captureAction != CaptureAction.START_DISTANCE_INTERVAL) return 0
        val distance = pass.zipWithNext().sumOf { (a, b) -> distanceMeters(a.point, b.point) }
        return maxOf(2, ceil(distance / requireNotNull(start.captureIntervalMeters)).toInt() + 1)
    }

    private fun boundingRoi(points: List<GeoPoint>): List<GeoPoint> {
        val centerLatitude = points.map { it.latitude }.average()
        val latitudePadding = 5.0 / 111_132.0
        val longitudePadding = 5.0 /
            (111_320.0 * cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.01))
        val minimumLatitude = points.minOf { it.latitude } - latitudePadding
        val maximumLatitude = points.maxOf { it.latitude } + latitudePadding
        val minimumLongitude = points.minOf { it.longitude } - longitudePadding
        val maximumLongitude = points.maxOf { it.longitude } + longitudePadding
        val altitude = points.minOf { it.altitudeMeters }
        return listOf(
            GeoPoint(minimumLatitude, minimumLongitude, altitude),
            GeoPoint(minimumLatitude, maximumLongitude, altitude),
            GeoPoint(maximumLatitude, maximumLongitude, altitude),
            GeoPoint(maximumLatitude, minimumLongitude, altitude),
        )
    }

    private fun distanceMeters(start: GeoPoint, end: GeoPoint): Double {
        val meanLatitude = (start.latitude + end.latitude) / 2.0
        val north = (end.latitude - start.latitude) * 111_132.0
        val east = (end.longitude - start.longitude) * 111_320.0 *
            cos(Math.toRadians(meanLatitude))
        return hypot(north, east)
    }
}
