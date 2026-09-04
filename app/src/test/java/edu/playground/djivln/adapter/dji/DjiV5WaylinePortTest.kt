package edu.playground.djivln.adapter.dji

import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.domain.wayline.WaylineActionEvent
import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DjiV5WaylinePortTest {
    @Test fun synchronousEmptySdkExecutingInfoDoesNotCrashOrInventMissionName() {
        val client = RecordingClient().apply { initialExecutingInfo = Triple("", 0, 0) }
        val port = DjiV5WaylinePort(client)

        port.start { }

        assertEquals(null, port.state().missionFileName)
        assertEquals(0, port.state().waylineId)
        assertEquals(0, port.state().waypointIndex)
    }

    @Test fun emptySdkExecutingInfoPreservesUploadedMissionName() {
        val file = File.createTempFile("survey.v2", ".kmz")
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.upload(file.absolutePath) { assertTrue(it.isSuccess) }

        client.executingInfo?.invoke(" ", 0, 0)

        assertEquals(file.nameWithoutExtension, port.state().missionFileName)
    }

    @Test fun startAndStopUseMissionBasenameWithoutKmzSuffix() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)

        port.execute("/tmp/survey.v2.kmz") { assertTrue(it.isSuccess) }
        port.stopMission("survey.v2.KMZ") { assertTrue(it.isSuccess) }

        assertEquals("survey.v2", client.executedMissionName)
        assertEquals("survey.v2", client.stoppedMissionName)
    }

    @Test fun uploadStateAndSdkExecutingInfoUseSameNormalizedName() {
        val file = File.createTempFile("survey.v2", ".kmz")
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }

        port.upload(file.absolutePath) { assertTrue(it.isSuccess) }
        val uploadedName = port.state().missionFileName
        client.executingInfo?.invoke(file.nameWithoutExtension, 0, 2)

        assertEquals(file.nameWithoutExtension, uploadedName)
        assertEquals(uploadedName, port.state().missionFileName)
        assertEquals(2, port.state().waypointIndex)
        assertEquals(WaylineBreakpoint(0, 2, 0.0), port.state().breakpoint)
    }

    @Test fun executingInfoContinuouslyAdvancesConservativeRecoveryBreakpoint() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }

        client.executingInfo?.invoke("survey", 0, 2)
        client.executingInfo?.invoke("survey", 0, 3)

        assertEquals(WaylineBreakpoint(0, 3, 0.0), port.state().breakpoint)
    }

    @Test fun queriedPreciseBreakpointIsNotDowngradedBySameWaypointProgressUpdate() {
        val precise = WaylineBreakpoint(0, 3, 0.65)
        val client = RecordingClient().apply { queriedBreakpoint = precise }
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.execute("survey.kmz") { }
        client.interrupt?.invoke("thermal return")

        client.executingInfo?.invoke("survey", 0, 3)

        assertEquals(precise, port.state().breakpoint)
    }

    @Test fun breakpointQueryAndRestartPreserveFields() {
        val breakpoint = WaylineBreakpoint(
            waylineId = 3,
            waypointId = 7,
            segmentProgress = 0.4,
            recoverActionType = "GoBackToRecordPoint",
        )
        val client = RecordingClient().apply {
            queriedBreakpoint = breakpoint
            availableIds = listOf(3)
        }
        val port = DjiV5WaylinePort(client)
        var queried: WaylineBreakpoint? = null

        port.queryBreakpoint("survey.kmz") {
            assertTrue(it.isSuccess)
            queried = it.getOrNull()
        }
        port.executeFromBreakpoint("survey.kmz", checkNotNull(queried)) { assertTrue(it.isSuccess) }

        assertEquals(breakpoint, client.executedBreakpoint)
        assertEquals("survey", client.executedMissionName)
        assertEquals(WaylinePhase.RECOVERING, port.state().phase)
    }

    @Test fun interruptionQueriesAndPublishesAircraftBreakpoint() {
        val breakpoint = WaylineBreakpoint(0, 4, 0.25)
        val client = RecordingClient().apply { queriedBreakpoint = breakpoint }
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.execute("survey.kmz") { }

        client.interrupt?.invoke("stick override")

        assertEquals(WaylinePhase.PAUSED, port.state().phase)
        assertEquals(breakpoint, port.state().breakpoint)
    }

    @Test fun lateInterruptAfterFinishedAndReadyDoesNotRestorePausedState() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.execute("survey.kmz") { assertTrue(it.isSuccess) }
        client.phase?.invoke(WaylinePhase.EXECUTING, "executing")
        client.phase?.invoke(WaylinePhase.FINISHED, "finished")
        client.phase?.invoke(WaylinePhase.READY, "ready")

        client.interrupt?.invoke("USER_BREAK")
        client.phase?.invoke(WaylinePhase.PAUSED, "late interrupted state")

        assertEquals(WaylinePhase.READY, port.state().phase)
        assertEquals(UiText.resource(R.string.wayline_dji_state, "ready"), port.state().message)
        assertEquals(1, client.breakpointQueryCount)
    }

    @Test fun preciseBreakpointQueryMayFinishAfterTerminalStateWithoutBeingDiscarded() {
        val precise = WaylineBreakpoint(0, 4, 0.77, 31.0, 121.0, 40.0, "GoBackToRecordPoint")
        val client = RecordingClient().apply {
            queriedBreakpoint = precise
            deferBreakpointQuery = true
        }
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.execute("survey.kmz") { assertTrue(it.isSuccess) }
        client.phase?.invoke(WaylinePhase.EXECUTING, "executing")
        client.interrupt?.invoke("USER_BREAK")
        client.phase?.invoke(WaylinePhase.FINISHED, "finished")
        client.phase?.invoke(WaylinePhase.READY, "ready")

        client.completeBreakpointQuery()

        assertEquals(WaylinePhase.READY, port.state().phase)
        assertEquals(precise, port.state().breakpoint)
    }

    @Test fun unexpectedFinishedStateQueriesPreciseBreakpointBeforeRecovery() {
        val precise = WaylineBreakpoint(0, 3, 0.42)
        val client = RecordingClient().apply { queriedBreakpoint = precise }
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.execute("survey.kmz") { assertTrue(it.isSuccess) }
        client.phase?.invoke(WaylinePhase.EXECUTING, "executing")

        client.phase?.invoke(WaylinePhase.FINISHED, "finished")

        assertEquals(1, client.breakpointQueryCount)
        assertEquals(precise, port.state().breakpoint)
        assertEquals(WaylinePhase.FINISHED, port.state().phase)
    }

    @Test fun executingInfoDoesNotQueryBreakpointBeforeMissionIsInterrupted() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }

        client.executingInfo?.invoke("survey", 0, 3)

        assertEquals(0, client.breakpointQueryCount)
    }

    @Test fun rejectsUnavailableWaylineBeforeCallingDjiStart() {
        val client = RecordingClient().apply { availableIds = listOf(0) }
        val port = DjiV5WaylinePort(client)
        var result: Result<Unit>? = null

        port.execute("survey.kmz", listOf(7)) { result = it }

        assertTrue(checkNotNull(result).isFailure)
        assertEquals(null, client.executedMissionName)
    }

    @Test fun emptyAvailableWaylineListStillAllowsExecuteAllForCompatibleAircraft() {
        val client = RecordingClient().apply { availableIds = emptyList() }
        val port = DjiV5WaylinePort(client)
        var result: Result<Unit>? = null

        port.execute("survey.kmz") { result = it }

        assertTrue(checkNotNull(result).isSuccess)
        assertEquals("survey", client.executedMissionName)
    }

    @Test fun freshExecuteClearsStaleWaypointProgressBeforeDjiStart() {
        val client = RecordingClient().apply {
            initialExecutingInfo = Triple("old", 4, 27)
        }
        val port = DjiV5WaylinePort(client)
        port.start { }

        port.execute("survey.kmz") { }

        assertEquals(WaylinePhase.PREPARING, port.state().phase)
        assertEquals(null, port.state().waylineId)
        assertEquals(null, port.state().waypointIndex)
        assertEquals(null, port.state().breakpoint)
    }

    @Test fun telemetryConfirmationPromotesOnlyAnActiveStart() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)

        assertEquals(false, port.confirmExecutionFromTelemetry())
        port.execute("survey.kmz") { assertTrue(it.isSuccess) }

        assertEquals(true, port.confirmExecutionFromTelemetry())
        assertEquals(WaylinePhase.EXECUTING, port.state().phase)
        assertEquals(false, port.confirmExecutionFromTelemetry())
    }

    @Test fun emptyAvailableWaylineListDoesNotBlockPersistedBreakpointRecovery() {
        val breakpoint = WaylineBreakpoint(0, 2, 0.5)
        val client = RecordingClient().apply { availableIds = emptyList() }
        val port = DjiV5WaylinePort(client)
        var result: Result<Unit>? = null

        port.executeFromBreakpoint("survey.kmz", breakpoint) { result = it }

        assertTrue(checkNotNull(result).isSuccess)
        assertEquals(breakpoint, client.executedBreakpoint)
    }

    @Test fun actionEventsArePublishedForPersistentSurveyLogging() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }

        val event = WaylineActionEvent(actionGroupId = 2, actionId = 9, started = false)
        client.action?.invoke(event)

        assertEquals(event, port.state().actionEvent)
    }

    @Test fun successfulExecutingStateClearsStaleOperationError() {
        val client = RecordingClient()
        val port = DjiV5WaylinePort(client)
        port.start { }
        port.execute("survey.kmz") { }
        client.phase?.invoke(WaylinePhase.EXECUTING, "executing")
        client.interrupt?.invoke("CANNOT_START_WAYLINE_WHEN_WAYLINE_RUNNING")

        client.phase?.invoke(WaylinePhase.EXECUTING, "executing again")

        assertEquals(WaylinePhase.EXECUTING, port.state().phase)
        assertEquals(null, port.state().error)
    }

    private class RecordingClient : DjiWaylineClient {
        var availableIds = listOf(0)
        var executedMissionName: String? = null
        var stoppedMissionName: String? = null
        var executedBreakpoint: WaylineBreakpoint? = null
        var queriedBreakpoint: WaylineBreakpoint? = null
        var deferBreakpointQuery = false
        var breakpointQueryCount = 0
        var pendingBreakpointQuery: ((Result<WaylineBreakpoint?>) -> Unit)? = null
        var phase: ((WaylinePhase, String) -> Unit)? = null
        var executingInfo: ((String?, Int?, Int?) -> Unit)? = null
        var initialExecutingInfo: Triple<String?, Int?, Int?>? = null
        var interrupt: ((String) -> Unit)? = null
        var action: ((WaylineActionEvent) -> Unit)? = null

        override fun start(
            onPhase: (WaylinePhase, String) -> Unit,
            onExecutingInfo: (String?, Int?, Int?) -> Unit,
            onInterrupt: (String) -> Unit,
            onAction: (WaylineActionEvent) -> Unit,
        ) {
            phase = onPhase
            executingInfo = onExecutingInfo
            interrupt = onInterrupt
            action = onAction
            initialExecutingInfo?.let { (missionName, waylineId, waypointIndex) ->
                onExecutingInfo(missionName, waylineId, waypointIndex)
            }
        }

        override fun stop() = Unit

        override fun upload(kmzPath: String, onProgress: (Double) -> Unit, completion: (Result<Unit>) -> Unit) {
            onProgress(1.0)
            completion(Result.success(Unit))
        }

        override fun availableWaylineIds(missionFileName: String): List<Int> = availableIds

        override fun execute(
            missionFileName: String,
            waylineIds: List<Int>,
            completion: (Result<Unit>) -> Unit,
        ) {
            executedMissionName = missionFileName
            completion(Result.success(Unit))
        }

        override fun executeFromBreakpoint(
            missionFileName: String,
            breakpoint: WaylineBreakpoint,
            completion: (Result<Unit>) -> Unit,
        ) {
            executedMissionName = missionFileName
            executedBreakpoint = breakpoint
            completion(Result.success(Unit))
        }

        override fun pause(completion: (Result<Unit>) -> Unit) = completion(Result.success(Unit))

        override fun resume(breakpoint: WaylineBreakpoint?, completion: (Result<Unit>) -> Unit) {
            executedBreakpoint = breakpoint
            completion(Result.success(Unit))
        }

        override fun queryBreakpoint(
            missionFileName: String,
            completion: (Result<WaylineBreakpoint?>) -> Unit,
        ) {
            breakpointQueryCount += 1
            if (deferBreakpointQuery) pendingBreakpointQuery = completion
            else completion(Result.success(queriedBreakpoint))
        }

        fun completeBreakpointQuery() {
            pendingBreakpointQuery?.invoke(Result.success(queriedBreakpoint))
            pendingBreakpointQuery = null
        }

        override fun stopMission(missionFileName: String, completion: (Result<Unit>) -> Unit) {
            stoppedMissionName = missionFileName
            completion(Result.success(Unit))
        }
    }
}
