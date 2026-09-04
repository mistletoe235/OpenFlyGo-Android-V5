package edu.playground.djivln.control

class ExternalSimulationInterlock {
    private var confirmed = false

    fun confirmForCurrentSession() {
        confirmed = true
    }

    fun revoke() {
        confirmed = false
    }

    fun isConfirmed(): Boolean = confirmed
}
