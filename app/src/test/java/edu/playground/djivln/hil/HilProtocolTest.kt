package edu.playground.djivln.hil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HilProtocolTest {
    @Test
    fun poseRoundTripsWithoutCoordinateReinterpretation() {
        val pose = HilProtocol.Pose(
            sampleMonotonicNanos = 1234L,
            originLatitudeDegrees = 31.0,
            originLongitudeDegrees = 121.0,
            eastMeters = 4.0,
            northMeters = 5.0,
            upMeters = 6.0,
            rollDegrees = 1.0,
            pitchDegrees = 2.0,
            headingDegreesClockwiseFromNorth = 90.0,
            velocityNorthMetersPerSecond = 0.5,
            velocityEastMetersPerSecond = 0.6,
            velocityUpMetersPerSecond = 0.7,
            gimbalPitchDegrees = -30.0,
            commandForwardMetersPerSecond = 1.1,
            commandRightMetersPerSecond = 1.2,
            commandUpMetersPerSecond = 1.3,
            commandYawRateDegreesPerSecond = 15.0,
            flightStateAgeMillis = 80,
            measuredSimulatorHz = 99.5f,
            stateFlags = HilProtocol.POSE_FLAG_FLYING,
        )
        val datagram = HilProtocol.decode(HilProtocol.encodePose(7L, 9L, pose))

        assertEquals(HilProtocol.TYPE_POSE, datagram.header.type)
        assertEquals(7L, datagram.header.sessionId)
        assertEquals(9L, datagram.header.sequence)
        assertEquals(pose, HilProtocol.decodePose(datagram.payload))
    }

    @Test
    fun eventRoundTripsUtf8Reason() {
        val event = HilProtocol.Event(77L, HilProtocol.EVENT_COLLISION, 0.91, 42L, "树干碰撞")
        val decoded = HilProtocol.decode(HilProtocol.encodeEvent(1L, 2L, event))
        assertEquals(event, HilProtocol.decodeEvent(decoded.payload))
    }

    @Test
    fun corruptHeaderIsRejected() {
        val bytes = HilProtocol.encodePing(1L, 2L, HilProtocol.Ping(3L))
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) { HilProtocol.decode(bytes) }
    }

    @Test
    fun poseMatchesSharedIosAndroidGoldenBytes() {
        val pose = HilProtocol.Pose(
            1L, 31.0, 121.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0,
            7.0, 8.0, 9.0, -45.0, 1.0, 2.0, 3.0, 4.0, 20, 20f, 7,
        )
        assertArrayEquals(SHARED_POSE_GOLDEN.hexBytes(), HilProtocol.encodePose(12L, 34L, pose))
    }

    @Test
    fun invalidTypeEventKindUtf8AndInfiniteScoreAreRejected() {
        val invalidType = HilProtocol.encodePing(1L, 2L, HilProtocol.Ping(3L)).also {
            it[6] = 0
            it[7] = 7
        }
        assertThrows(IllegalArgumentException::class.java) { HilProtocol.decode(invalidType) }

        fun eventBytes(event: HilProtocol.Event) = HilProtocol.encodeEvent(1L, 2L, event)
        val invalidKind = eventBytes(HilProtocol.Event(1L, HilProtocol.EVENT_INFO, 0.5, 2L, "x")).also {
            it[43] = 9
        }
        assertThrows(IllegalArgumentException::class.java) {
            HilProtocol.decodeEvent(HilProtocol.decode(invalidKind).payload)
        }

        val invalidUtf8 = eventBytes(HilProtocol.Event(1L, HilProtocol.EVENT_INFO, 0.5, 2L, "x")).also {
            it[64] = 0x80.toByte()
        }
        assertThrows(Exception::class.java) {
            HilProtocol.decodeEvent(HilProtocol.decode(invalidUtf8).payload)
        }

        val infinite = eventBytes(HilProtocol.Event(1L, HilProtocol.EVENT_INFO, Double.POSITIVE_INFINITY, 2L, ""))
        assertThrows(IllegalArgumentException::class.java) {
            HilProtocol.decodeEvent(HilProtocol.decode(infinite).payload)
        }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val SHARED_POSE_GOLDEN =
            "4f46484c0001000200000000000000000000000c000000000000002200000098" +
                "0000000000000001403f000000000000405e4000000000003ff0000000000000" +
                "4000000000000000400800000000000040100000000000004014000000000000" +
                "4018000000000000401c00000000000040200000000000004022000000000000" +
                "c0468000000000003ff000000000000040000000000000004008000000000000" +
                "40100000000000000000001441a000000000000700000000"
    }
}
