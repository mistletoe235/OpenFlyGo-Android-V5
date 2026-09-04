package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.telemetry.AircraftSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FakeAircraftTelemetrySourceTest {
    @Test
    fun emitsTimestampedSnapshotsAndStopsDeterministically() {
        val source = FakeAircraftTelemetrySource(clockNanos = { 1234L })
        var received: AircraftSnapshot? = null
        source.start { received = it }

        source.emit(AircraftSnapshot(connected = true, productId = 9))
        assertEquals(1234L, received?.updatedAtNanos)
        assertEquals(9, received?.productId)

        source.stop()
        source.emit(AircraftSnapshot(connected = true, productId = 10))
        assertEquals(9, received?.productId)
    }

    @Test
    fun disconnectClearsStaleFlightValues() {
        val source = FakeAircraftTelemetrySource(clockNanos = { 99L })
        source.emit(AircraftSnapshot(connected = true, relativeAltitudeMeters = 12.0))

        source.disconnect()

        assertFalse(source.snapshot().connected)
        assertNull(source.snapshot().relativeAltitudeMeters)
        assertEquals(99L, source.snapshot().updatedAtNanos)
    }
}
