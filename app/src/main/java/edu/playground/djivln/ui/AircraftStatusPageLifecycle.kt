package edu.playground.djivln.ui

/** Keeps telemetry callbacks from rendering a partially assembled status page. */
internal class AircraftStatusPageLifecycle {
    enum class State {
        IDLE,
        BUILDING,
        READY,
    }

    var state: State = State.IDLE
        private set

    val canRender: Boolean
        get() = state == State.READY

    fun beginBuild() {
        state = State.BUILDING
    }

    fun completeBuild() {
        check(state == State.BUILDING) { "status page build was not started" }
        state = State.READY
    }

    fun failBuild() {
        state = State.IDLE
    }
}
