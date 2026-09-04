package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import edu.playground.djivln.domain.telemetry.AircraftSnapshotListener
import edu.playground.djivln.domain.telemetry.AircraftTelemetrySource

class FakeAircraftTelemetrySource(
    initialSnapshot: AircraftSnapshot = AircraftSnapshot(),
    private val clockNanos: () -> Long = System::nanoTime
) : AircraftTelemetrySource {
    private var current = initialSnapshot
    private var listener: AircraftSnapshotListener? = null

    override fun start(listener: AircraftSnapshotListener) {
        this.listener = listener
        listener.onSnapshot(current)
    }

    override fun stop() {
        listener = null
    }

    override fun snapshot(): AircraftSnapshot = current

    fun emit(snapshot: AircraftSnapshot) {
        current = snapshot.copy(updatedAtNanos = clockNanos())
        listener?.onSnapshot(current)
    }

    fun disconnect() {
        emit(AircraftSnapshot(connected = false))
    }
}
