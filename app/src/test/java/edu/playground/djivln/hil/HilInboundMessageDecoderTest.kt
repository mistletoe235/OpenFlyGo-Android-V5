package edu.playground.djivln.hil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HilInboundMessageDecoderTest {
    @Test fun validPingIsDecodedExactlyOnceAndKeepsItsTimestamp() {
        val datagram = HilProtocol.decode(HilProtocol.encodePing(1L, 2L, HilProtocol.Ping(3L)))
        val inbound = HilInboundMessageDecoder.decode(datagram)
        assertTrue(inbound is HilInboundMessage.Ping)
        assertEquals(3L, (inbound as HilInboundMessage.Ping).value.senderMonotonicNanos)
    }

    @Test fun outboundOnlyPoseIsRejectedBeforePeerFreshness() {
        val pose = HilProtocol.Pose(
            1L, 31.0, 121.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0f, 0,
        )
        val datagram = HilProtocol.decode(HilProtocol.encodePose(1L, 2L, pose))
        assertThrows(IllegalArgumentException::class.java) { HilInboundMessageDecoder.decode(datagram) }
    }

    @Test fun nonzeroHeaderFlagsAreRejected() {
        val bytes = HilProtocol.encodePing(1L, 2L, HilProtocol.Ping(3L)).also { it[11] = 1 }
        val datagram = HilProtocol.decode(bytes)
        assertThrows(IllegalArgumentException::class.java) { HilInboundMessageDecoder.decode(datagram) }
    }
}
