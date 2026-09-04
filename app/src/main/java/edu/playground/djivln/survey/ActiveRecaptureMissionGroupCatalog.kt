package edu.playground.djivln.survey

import android.content.Context
import edu.playground.djivln.R
data class ActiveRecaptureMissionGroup(
    val groupId: String,
    val order: Int,
    val label: String,
    val regionIds: Set<String>,
    val suggestedSurveyPhotos: Int,
    val targetWgs84: ActiveMappingTarget?,
)

object ActiveRecaptureMissionGroupCatalog {
    private data class Definition(
        val groupId: String,
        val order: Int,
        val labelRes: Int,
        val defaultLabel: String,
        val kinds: Set<String>,
    )

    private val v30Definitions = listOf(
        Definition("V30_HIGH_RISE", 1, R.string.recapture_group_west_high_rise, "West high-rise five-direction", setOf("HIGH_RISE_FIVE_DIRECTION")),
        Definition("V30_LARGE", 2, R.string.recapture_group_east_large, "East large-area five-direction", setOf("LARGE_FIVE_DIRECTION")),
        Definition("V30_SMALL_CROSS", 3, R.string.recapture_group_small_cross, "Small cross recapture", setOf("SMALL_CROSS")),
        Definition("V30_RISK_SCAN", 4, R.string.recapture_group_risk_scan, "Scattered risk recapture", setOf("V26_RISK_SCAN")),
    )

    fun groups(mission: SurveyMission, context: Context? = null): List<ActiveRecaptureMissionGroup> {
        val regions = mission.activeMapping?.regions.orEmpty().sortedBy { it.priority }
        if (regions.isEmpty()) return emptyList()
        val knownKinds = v30Definitions.flatMap { it.kinds }.toSet()
        if (regions.all { it.kind in knownKinds }) {
            return v30Definitions.mapNotNull { definition ->
                val matching = regions.filter { it.kind in definition.kinds }
                if (matching.isEmpty()) return@mapNotNull null
                ActiveRecaptureMissionGroup(
                    groupId = definition.groupId,
                    order = definition.order,
                    label = context?.getString(definition.labelRes) ?: definition.defaultLabel,
                    regionIds = matching.mapTo(linkedSetOf()) { it.regionId },
                    suggestedSurveyPhotos = matching.sumOf { it.suggestedSurveyPhotos },
                    targetWgs84 = averageTarget(matching.mapNotNull { it.targetWgs84 }),
                )
            }
        }
        return regions.mapIndexed { index, region ->
            ActiveRecaptureMissionGroup(
                groupId = region.regionId,
                order = index + 1,
                label = region.kind.replace('_', ' '),
                regionIds = setOf(region.regionId),
                suggestedSurveyPhotos = region.suggestedSurveyPhotos,
                targetWgs84 = region.targetWgs84,
            )
        }
    }

    fun selectedGroupIds(source: SurveyMission, selected: SurveyMission): Set<String> {
        val selectedRegionIds = selected.activeMapping?.regions?.mapTo(hashSetOf()) { it.regionId }.orEmpty()
        return groups(source)
            .filter { group -> group.regionIds.all { it in selectedRegionIds } }
            .mapTo(linkedSetOf()) { it.groupId }
    }

    private fun averageTarget(targets: List<ActiveMappingTarget>): ActiveMappingTarget? {
        if (targets.isEmpty()) return null
        return ActiveMappingTarget(
            latitude = targets.map { it.latitude }.average(),
            longitude = targets.map { it.longitude }.average(),
            absoluteAltitudeMeters = targets.map { it.absoluteAltitudeMeters }.average(),
        )
    }
}
