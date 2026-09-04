package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.wayline.WaylineCompletion
import edu.playground.djivln.domain.wayline.WaylineBreakpoint
import edu.playground.djivln.domain.wayline.WaylineBreakpointCompletion
import edu.playground.djivln.domain.wayline.WaylinePhase
import edu.playground.djivln.domain.wayline.WaylinePort
import edu.playground.djivln.domain.wayline.WaylineState
import edu.playground.djivln.domain.wayline.WaylineStateListener
import edu.playground.djivln.R
import edu.playground.djivln.localization.UiText
import java.io.File

class FakeWaylinePort : WaylinePort {
    private var listener: WaylineStateListener? = null
    private var current = WaylineState()

    override fun start(listener: WaylineStateListener) {
        this.listener = listener
        listener.onStateChanged(current)
    }

    override fun stop() {
        listener = null
    }

    override fun state(): WaylineState = current

    override fun upload(kmzPath: String, completion: WaylineCompletion) {
        val file = File(kmzPath)
        if (!file.isFile) {
            fail("KMZ file does not exist", completion)
            return
        }
        publish(WaylineState(WaylinePhase.UPLOADING, file.name, uploadProgress = 0.5, message = UiText.resource(R.string.wayline_uploading)))
        publish(current.copy(phase = WaylinePhase.READY, uploadProgress = 1.0, message = UiText.resource(R.string.wayline_ready)))
        completion.complete(Result.success(Unit))
    }

    override fun execute(missionFileName: String, waylineIds: List<Int>, completion: WaylineCompletion) {
        publish(current.copy(
            phase = WaylinePhase.EXECUTING,
            missionFileName = missionFileName,
            waylineId = waylineIds.firstOrNull(),
            waypointIndex = 0,
            message = UiText.resource(R.string.wayline_executing)
        ))
        completion.complete(Result.success(Unit))
    }

    override fun executeFromBreakpoint(
        missionFileName: String,
        breakpoint: WaylineBreakpoint,
        completion: WaylineCompletion,
    ) {
        publish(
            current.copy(
                phase = WaylinePhase.EXECUTING,
                missionFileName = missionFileName,
                waylineId = breakpoint.waylineId,
                waypointIndex = breakpoint.waypointId,
                breakpoint = breakpoint,
                message = UiText.resource(R.string.wayline_executing_from_breakpoint),
            ),
        )
        completion.complete(Result.success(Unit))
    }

    override fun pause(completion: WaylineCompletion) {
        publish(current.copy(phase = WaylinePhase.PAUSED, message = UiText.resource(R.string.wayline_paused)))
        completion.complete(Result.success(Unit))
    }

    override fun resume(completion: WaylineCompletion) {
        publish(current.copy(phase = WaylinePhase.EXECUTING, message = UiText.resource(R.string.wayline_resumed)))
        completion.complete(Result.success(Unit))
    }

    override fun resume(breakpoint: WaylineBreakpoint, completion: WaylineCompletion) {
        publish(
            current.copy(
                phase = WaylinePhase.EXECUTING,
                waylineId = breakpoint.waylineId,
                waypointIndex = breakpoint.waypointId,
                breakpoint = breakpoint,
                message = UiText.resource(R.string.wayline_resumed_from_breakpoint),
            ),
        )
        completion.complete(Result.success(Unit))
    }

    override fun queryBreakpoint(missionFileName: String, completion: WaylineBreakpointCompletion) {
        completion.complete(Result.success(current.breakpoint))
    }

    override fun stopMission(missionFileName: String, completion: WaylineCompletion) {
        publish(WaylineState(message = UiText.resource(R.string.wayline_stopped)))
        completion.complete(Result.success(Unit))
    }

    fun advance(waypointIndex: Int) {
        publish(current.copy(waypointIndex = waypointIndex))
    }

    private fun fail(message: String, completion: WaylineCompletion) {
        publish(WaylineState(WaylinePhase.ERROR, message = UiText.external(message), error = message))
        completion.complete(Result.failure(IllegalArgumentException(message)))
    }

    private fun publish(state: WaylineState) {
        current = state
        listener?.onStateChanged(state)
    }
}
