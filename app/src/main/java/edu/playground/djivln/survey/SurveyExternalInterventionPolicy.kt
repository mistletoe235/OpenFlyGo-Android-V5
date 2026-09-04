package edu.playground.djivln.survey

import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText

object SurveyExternalInterventionPolicy {
    fun reason(flightMode: String?, goHomeState: String?, lowBatteryRthState: String?): UiText? {
        val mode = flightMode.orEmpty().uppercase()
        val goHome = goHomeState.orEmpty().uppercase()
        val smartRth = lowBatteryRthState.orEmpty().uppercase()
        if (smartRth == "COUNTING_DOWN" || smartRth == "EXECUTED") {
            return UiText.resource(R.string.survey_smart_low_battery_rth, smartRth)
        }
        val modeActive = mode.contains("GO_HOME") || mode.contains("GOHOME") ||
            mode.contains("GO HOME") || mode.contains("LAND")
        val goHomeActive = goHome.isNotBlank() && goHome !in setOf("IDLE", "NONE", "UNKNOWN")
        val detail = when {
            modeActive -> flightMode
            goHomeActive -> goHome
            else -> null
        }
        return detail?.let { UiText.resource(R.string.survey_fc_rth_or_landing, it) }
    }
}
