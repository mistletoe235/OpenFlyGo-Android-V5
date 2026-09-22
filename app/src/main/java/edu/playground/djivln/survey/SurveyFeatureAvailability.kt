package edu.playground.djivln.survey

/** Keeps unfinished flight-critical features fail-closed while preserving their implementation. */
object SurveyFeatureAvailability {
    const val TERRAIN_FOLLOWING_ENABLED = false

    fun supportsMission(mission: SurveyMission): Boolean = TERRAIN_FOLLOWING_ENABLED || mission.terrainPlan == null

    fun requireSupportedMission(mission: SurveyMission, message: String = "Terrain-following missions are disabled in this build") {
        require(supportsMission(mission)) { message }
    }
}
