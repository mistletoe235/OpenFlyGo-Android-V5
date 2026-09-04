package edu.playground.djivln.hil

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Mini4PerceptionPacketCodecTest {
    @Test
    fun encodesObservedMini4LayoutAndRoundTrips() {
        val distances = List(360) { index -> if (index in 170..190) 516 else 60_000 }
        val observation = Mini4PerceptionPacketCodec.Observation(
            status = 0,
            upwardDistanceMillimeters = 1_614,
            downwardDistanceMillimeters = 19,
            horizontalDistanceMillimeters = distances,
        )

        val payload = Mini4PerceptionPacketCodec.encode(observation)

        assertEquals(728, payload.size)
        val header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, header.short.toInt())
        assertEquals(1_614, header.short.toInt() and 0xffff)
        assertEquals(19, header.short.toInt() and 0xffff)
        assertEquals(360, header.short.toInt() and 0xffff)
        assertEquals(observation, Mini4PerceptionPacketCodec.decode(payload))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPayload() {
        val valid = Mini4PerceptionPacketCodec.encode(
            Mini4PerceptionPacketCodec.Observation(0, 60_000, 60_000, List(360) { 60_000 }),
        )
        Mini4PerceptionPacketCodec.decode(valid.copyOf(valid.size - 1))
    }

    @Test
    fun commandIdentityMatchesNativeObserver() {
        assertArrayEquals(byteArrayOf(0x24, 0x08), byteArrayOf(
            Mini4PerceptionPacketCodec.COMMAND_SET.toByte(),
            Mini4PerceptionPacketCodec.COMMAND_ID.toByte(),
        ))
    }
}
