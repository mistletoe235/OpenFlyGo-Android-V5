package edu.playground.djivln.survey

import edu.playground.djivln.domain.wayline.WaylinePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SurveyAircraftBackendPolicyTest {
    private val kmz = SurveyExecutionBackend.DJI_KMZ
    private val virtualStick = SurveyExecutionBackend.CUSTOM_VIRTUAL_STICK
    private val hil = SurveyExecutionBackend.UE_HIL

    @Test
    fun mini3AliasesSelectVirtualStickWithIndependentCapabilityEvidence() {
        listOf("DJI_MINI_3", "DJI_MINI_3_PRO", "DJI Mini 3", "Mini 3 Pro", "mini-3-pro").forEach { product ->
            val decision = select(product)
            assertEquals(product, virtualStick, decision.backend)
            assertEquals(SurveyAircraftSupport.UNSUPPORTED, decision.capabilities.kmz)
            assertEquals(SurveyAircraftSupport.SUPPORTED, decision.capabilities.virtualStick)
            assertEquals(SurveyAircraftBackendPolicy.Reason.MINI_3_VIRTUAL_STICK, decision.reason)
            assertFalse(decision.incompatibleKmz)
        }
    }

    @Test
    fun mini4AndEnterpriseAircraftKeepKmzWithoutPromisingVirtualStickOrRecovery() {
        listOf(
            "DJI_MINI_4_PRO", "M300_RTK", "M350_RTK", "M30_SERIES",
            "DJI_MAVIC_3_ENTERPRISE_SERIES", "DJI_MATRICE_4_SERIES",
            "DJI_MATRICE_4D_SERIES", "DJI_MATRICE_400",
        ).forEach { product ->
            val decision = select(product)
            assertEquals(product, kmz, decision.backend)
            assertEquals(SurveyAircraftSupport.SUPPORTED, decision.capabilities.kmz)
            assertEquals(SurveyAircraftSupport.UNKNOWN, decision.capabilities.virtualStick)
        }
    }

    @Test
    fun unknownOrCameraProductNamesAreNotRejectedOrUsedForFallback() {
        listOf(null, "", "UNKNOWN", "UNRECOGNIZED", "FUTURE_AIRCRAFT", "Mini 3 Camera", "H30T").forEach { product ->
            val decision = select(product)
            assertEquals(kmz, decision.backend)
            assertEquals(SurveyAircraftSupport.UNKNOWN, decision.capabilities.kmz)
            assertFalse(decision.incompatibleKmz)
        }
    }

    @Test
    fun disconnectedCachedMini3DoesNotChangeBackendOrBlockKmz() {
        val decision = select("DJI_MINI_3", connected = false)
        assertEquals(kmz, decision.backend)
        assertEquals(SurveyAircraftSupport.UNKNOWN, decision.capabilities.kmz)
        assertFalse(decision.incompatibleKmz)
    }

    @Test
    fun explicitVirtualStickAndHilRemainSelectedOnEveryDevice() {
        listOf(virtualStick, hil).forEach { preferred ->
            listOf("DJI_MINI_3", "DJI_MINI_4_PRO", "FUTURE_AIRCRAFT", null).forEach { product ->
                assertEquals(preferred, select(product, preferred = preferred, current = preferred).backend)
            }
        }
    }

    @Test
    fun activeDjiPhasesDoNotConvertMini3KmzTasks() {
        listOf(WaylinePhase.UPLOADING, WaylinePhase.PREPARING, WaylinePhase.EXECUTING,
            WaylinePhase.PAUSED, WaylinePhase.RECOVERING).forEach { phase ->
            val decision = select("DJI_MINI_3", task = SurveyBackendTaskState(waylinePhase = phase))
            assertEquals(phase.name, kmz, decision.backend)
            assertTrue(decision.incompatibleKmz)
            assertEquals(SurveyAircraftBackendPolicy.Reason.TASK_RETAINED, decision.reason)
        }
    }

    @Test
    fun customArmingRunningAndPausedNeverSwitchBackToKmz() {
        listOf(SurveyExecutionState.ARMING, SurveyExecutionState.RUNNING, SurveyExecutionState.PAUSED).forEach { state ->
            val decision = select("DJI_MINI_4_PRO", current = virtualStick,
                task = SurveyBackendTaskState(customState = state))
            assertEquals(virtualStick, decision.backend)
        }
    }

    @Test
    fun pendingAndRestoredCheckpointsRemainKmzEvenWhenSdkIdleOrErrored() {
        WaylinePhase.values().forEach { phase ->
            val decision = select("DJI_MINI_3_PRO",
                task = SurveyBackendTaskState(waylinePhase = phase, hasCheckpoint = true))
            assertEquals(kmz, decision.backend)
            assertTrue(decision.incompatibleKmz)
        }
    }

    @Test
    fun customCheckpointRetainsBackendDespiteDefaultKmzPreference() {
        assertEquals(virtualStick, select("DJI_MINI_4_PRO", current = virtualStick,
            task = SurveyBackendTaskState(hasCheckpoint = true)).backend)
    }

    @Test
    fun preparingAndUploadedTasksDoNotConvert() {
        listOf(
            SurveyBackendTaskState(preparing = true),
            SurveyBackendTaskState(waylinePhase = WaylinePhase.READY, hasUploadedKmz = true),
        ).forEach { task ->
            val decision = select("DJI_MINI_3", task = task)
            assertEquals(kmz, decision.backend)
            assertTrue(decision.incompatibleKmz)
        }
    }

    @Test
    fun stoppingAndClearingOldCheckpointAllowsNewMini3TaskToUseVirtualStick() {
        val retained = select("DJI_MINI_3", task = SurveyBackendTaskState(hasCheckpoint = true))
        val fresh = select("DJI_MINI_3", current = retained.backend)
        assertTrue(retained.incompatibleKmz)
        assertEquals(virtualStick, fresh.backend)
        assertFalse(fresh.incompatibleKmz)
    }

    @Test
    fun reconnectingMini4RestoresKmzPreferenceOnlyWhenTaskUnlocked() {
        val mini3 = select("DJI_MINI_3")
        val offline = select("DJI_MINI_3", current = mini3.backend, connected = false)
        val loading = select(null, current = offline.backend)
        val mini4 = select("DJI_MINI_4_PRO", current = loading.backend)
        assertEquals(virtualStick, offline.backend)
        assertEquals(virtualStick, loading.backend)
        assertEquals(kmz, mini4.backend)
        assertEquals(SurveyAircraftBackendPolicy.Reason.KMZ_PREFERENCE_RESTORED, mini4.reason)
    }

    @Test
    fun explicitlyKeepingVirtualStickAfterFallbackDoesNotRestoreKmz() {
        assertEquals(virtualStick, select("DJI_MINI_4_PRO", preferred = virtualStick, current = virtualStick).backend)
    }

    @Test
    fun sdkUnsupportedErrorsOrDisconnectDoNotImplyVirtualStickCapability() {
        listOf(WaylinePhase.UNSUPPORTED, WaylinePhase.ERROR, WaylinePhase.DISCONNECTED).forEach { phase ->
            listOf("DJI_MINI_4_PRO", "FUTURE_AIRCRAFT", null).forEach { product ->
                val decision = select(product, task = SurveyBackendTaskState(waylinePhase = phase))
                assertEquals(kmz, decision.backend)
                assertFalse(decision.incompatibleKmz)
            }
        }
    }

    @Test
    fun actualMini3ExceptionWorksEvenWhenNativeServiceSaysUnsupported() {
        val decision = select("DJI_MINI_3_PRO", task = SurveyBackendTaskState(waylinePhase = WaylinePhase.UNSUPPORTED))
        assertEquals(virtualStick, decision.backend)
    }

    @Test
    fun normalizationIsIndependentOfPhoneLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(virtualStick, select("dji mini 3").backend)
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun select(
        product: String?,
        preferred: SurveyExecutionBackend = kmz,
        current: SurveyExecutionBackend = kmz,
        connected: Boolean = true,
        task: SurveyBackendTaskState = SurveyBackendTaskState(),
    ) = SurveyAircraftBackendPolicy.select(product, connected, preferred, current, task)
}
