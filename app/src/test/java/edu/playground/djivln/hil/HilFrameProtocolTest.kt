package edu.playground.djivln.hil

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HilFrameProtocolTest {
    @Test
    fun readsCompleteCrcCheckedFrame() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = frameBytes(payload, CRC32().apply { update(payload) }.value)
        val frame = HilFrameProtocol.read(DataInputStream(ByteArrayInputStream(bytes)), 999L)
        assertEquals(11L, frame.frameId)
        assertEquals(12L, frame.poseSequence)
        assertEquals(1440, frame.width)
        assertEquals(1080, frame.height)
        assertArrayEquals(payload, frame.encoded)
    }

    @Test
    fun rejectsCrcMismatch() {
        val bytes = frameBytes(byteArrayOf(1, 2, 3), 0L)
        assertThrows(IllegalArgumentException::class.java) {
            HilFrameProtocol.read(DataInputStream(ByteArrayInputStream(bytes)), 1L)
        }
    }

    @Test
    fun borrowLatestFrameIfNewSharesOnlyNewReadOnlyFrames() {
        val store = HilVirtualFrameStore()
        val offered = HilFrameProtocol.Frame(
            HilFrameProtocol.FORMAT_JPEG, 7L, 3L, 10L, 20L,
            4, 3, 0, byteArrayOf(1, 2, 3), streamGeneration = 2L,
        )
        store.offer(offered)

        val first = store.borrowLatestFrameIfNew(Long.MAX_VALUE, 1L, 99L, 20L)
        assertEquals(7L, first?.frameId)
        assertArrayEquals(offered.encoded, first?.encoded)
        assertSame(offered.encoded, first?.encoded)
        assertEquals(null, store.borrowLatestFrameIfNew(Long.MAX_VALUE, 2L, 7L, 20L))
    }

    private fun frameBytes(payload: ByteArray, crc: Long): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(HilFrameProtocol.MAGIC)
            data.writeShort(HilFrameProtocol.VERSION.toInt())
            data.writeShort(HilFrameProtocol.FORMAT_JPEG.toInt())
            data.writeInt(HilFrameProtocol.HEADER_BYTES)
            data.writeInt(payload.size)
            data.writeLong(11L)
            data.writeLong(12L)
            data.writeLong(13L)
            data.writeInt(1440)
            data.writeInt(1080)
            data.writeInt(0)
            data.writeInt(crc.toInt())
            data.write(payload)
        }
    }.toByteArray()
}
