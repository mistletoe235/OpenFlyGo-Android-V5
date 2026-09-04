package edu.playground.djivln.survey

enum class SurveyFailsafeAction {
    CONTINUE,
    PAUSE_ZERO_AND_RELEASE,
    ABORT_ZERO_AND_RELEASE,
}

data class SurveyFailsafeDecision(
    val action: SurveyFailsafeAction,
    val reason: String?,
)

/** Pure runtime watchdog. The Android adapter must enact ZERO_AND_RELEASE atomically. */
object SurveyExecutionWatchdog {
    fun inspect(
        state: SurveyExecutionState,
        gate: SurveyExecutionGateResult,
        trustedDjiTelemetry: Boolean,
        nowElapsedMillis: Long,
        waypointDeadlineElapsedMillis: Long,
    ): SurveyFailsafeDecision {
        if (state != SurveyExecutionState.RUNNING) {
            return SurveyFailsafeDecision(SurveyFailsafeAction.CONTINUE, null)
        }
        if (!trustedDjiTelemetry) {
            return SurveyFailsafeDecision(
                SurveyFailsafeAction.ABORT_ZERO_AND_RELEASE,
                "untrusted simulator telemetry",
            )
        }
        if (!gate.allowed) {
            val interventionBlocks = setOf(
                SurveyExecutionBlock.MANUAL_TAKEOVER,
                SurveyExecutionBlock.FLIGHT_CONTROLLER_FAILSAFE_ACTIVE,
                SurveyExecutionBlock.VIRTUAL_STICK_REQUIRED,
            )
            if (gate.blocks.any { it in interventionBlocks }) {
                return SurveyFailsafeDecision(
                    SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE,
                    "external intervention: ${gate.blocks.joinToString(",")}",
                )
            }
            return SurveyFailsafeDecision(
                SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE,
                "runtime gate: ${gate.blocks.joinToString(",")}",
            )
        }
        if (waypointDeadlineElapsedMillis <= 0L || nowElapsedMillis >= waypointDeadlineElapsedMillis) {
            return SurveyFailsafeDecision(
                SurveyFailsafeAction.PAUSE_ZERO_AND_RELEASE,
                "waypoint tracking timeout",
            )
        }
        return SurveyFailsafeDecision(SurveyFailsafeAction.CONTINUE, null)
    }
}
