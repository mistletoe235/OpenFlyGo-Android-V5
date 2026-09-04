package edu.playground.djivln.hil

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import org.junit.Test

class HilRuntimeSessionTest {
    @Test fun incompleteTelemetryIsSafelyIgnored() {
        HilRuntimeSession().use { session ->
            session.submitTelemetry(AircraftSnapshot(connected = true))
        }
    }
}
