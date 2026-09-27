package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylinePhase
import java.util.Locale

enum class SurveyAircraftSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class SurveyAircraftCapabilities(
    val product: String,
    val kmz: SurveyAircraftSupport,
    val virtualStick: SurveyAircraftSupport,
)

data class SurveyBackendTaskState(
    val waylinePhase: WaylinePhase = WaylinePhase.IDLE,
    val customState: SurveyExecutionState = SurveyExecutionState.IDLE,
    val preparing: Boolean = false,
    val hasCheckpoint: Boolean = false,
    val hasUploadedKmz: Boolean = false,
) {
    val locked: Boolean
        get() = preparing || hasCheckpoint || hasUploadedKmz || waylinePhase in setOf(
            WaylinePhase.UPLOADING, WaylinePhase.PREPARING, WaylinePhase.EXECUTING,
            WaylinePhase.PAUSED, WaylinePhase.RECOVERING,
        ) || customState in setOf(
            SurveyExecutionState.ARMING, SurveyExecutionState.RUNNING, SurveyExecutionState.PAUSED,
        )
}

object SurveyAircraftBackendPolicy {
    enum class Reason { PREFERENCE, MINI_3_VIRTUAL_STICK, KMZ_PREFERENCE_RESTORED, TASK_RETAINED, DEVICE_UNKNOWN }

    data class Decision(
        val backend: SurveyExecutionBackend,
        val capabilities: SurveyAircraftCapabilities,
        val reason: Reason,
    ) {
        val incompatibleKmz: Boolean
            get() = backend == SurveyExecutionBackend.DJI_KMZ &&
                capabilities.kmz == SurveyAircraftSupport.UNSUPPORTED
    }

    private val knownKmzProducts = setOf(
        "MINI4PRO", "M300RTK", "M350RTK", "M30SERIES", "MAVIC3ENTERPRISESERIES",
        "MATRICE4SERIES", "MATRICE4DSERIES", "MATRICE400",
    )

    fun capabilities(productType: String?, connected: Boolean): SurveyAircraftCapabilities {
        val product = productType.orEmpty().uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }.removePrefix("DJI")
        if (!connected) return SurveyAircraftCapabilities(
            product, SurveyAircraftSupport.UNKNOWN, SurveyAircraftSupport.UNKNOWN,
        )
        return when (product) {
            "MINI3", "MINI3PRO" -> SurveyAircraftCapabilities(
                product, SurveyAircraftSupport.UNSUPPORTED, SurveyAircraftSupport.SUPPORTED,
            )
            in knownKmzProducts -> SurveyAircraftCapabilities(
                product, SurveyAircraftSupport.SUPPORTED, SurveyAircraftSupport.UNKNOWN,
            )
            else -> SurveyAircraftCapabilities(
                product, SurveyAircraftSupport.UNKNOWN, SurveyAircraftSupport.UNKNOWN,
            )
        }
    }

    fun select(
        productType: String?,
        connected: Boolean,
        preferred: SurveyExecutionBackend,
        current: SurveyExecutionBackend,
        task: SurveyBackendTaskState = SurveyBackendTaskState(),
    ): Decision {
        val capabilities = capabilities(productType, connected)
        if (task.locked) return Decision(current, capabilities, Reason.TASK_RETAINED)
        if (preferred != SurveyExecutionBackend.DJI_KMZ) {
            return Decision(preferred, capabilities, Reason.PREFERENCE)
        }
        if (capabilities.kmz == SurveyAircraftSupport.UNSUPPORTED &&
            capabilities.virtualStick == SurveyAircraftSupport.SUPPORTED
        ) {
            return Decision(SurveyExecutionBackend.CUSTOM_VIRTUAL_STICK, capabilities, Reason.MINI_3_VIRTUAL_STICK)
        }
        if (capabilities.kmz == SurveyAircraftSupport.SUPPORTED) {
            return Decision(preferred, capabilities, if (current == preferred) Reason.PREFERENCE else Reason.KMZ_PREFERENCE_RESTORED)
        }
        return Decision(current, capabilities, Reason.DEVICE_UNKNOWN)
    }
}
