package edu.playground.djivln.reconstruction

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JpegApp1PreserverTest {
    @Test
    fun `re-encoding preserves every APP1 block byte for byte`() {
        val exif = app1("Exif\u0000\u0000DJI-MAKER-NOTE")
        val xmp = app1("http://ns.adobe.com/xap/1.0/\u0000xmp")
        val original = jpeg(exif, xmp)
        val encodedWithoutMetadata = jpeg()

        val extracted = JpegApp1Preserver.extract(original)
        val restored = JpegApp1Preserver.inject(encodedWithoutMetadata, extracted)

        assertEquals(2, extracted.size)
        assertArrayEquals(exif, extracted[0])
        assertArrayEquals(xmp, extracted[1])
        val restoredBlocks = JpegApp1Preserver.extract(restored)
        assertArrayEquals(exif, restoredBlocks[0])
        assertArrayEquals(xmp, restoredBlocks[1])
    }

    @Test
    fun `DJI XMP is a safe fallback when Android EXIF GPS is unavailable`() {
        val xmp = app1(
            """<rdf:Description drone-dji:GpsLatitude="+31.026047295" """ +
                """drone-dji:GpsLongitude="+121.429170074" """ +
                """drone-dji:AbsoluteAltitude="+86.921" """ +
                """xmp:CreateDate="2026-07-25T17:17:40+08:00"/>""",
        )

        val inspection = V86OfflineImageCompressor().validate("DJI_M30T.JPG", jpeg(xmp).inputStream())

        assertEquals(31.026047295, inspection.latitude, 0.0)
        assertEquals(121.429170074, inspection.longitude, 0.0)
        assertEquals(86.921, requireNotNull(inspection.absoluteAltitudeMeters), 0.0)
        assertEquals("2026-07-25T17:17:40+08:00", inspection.timestamp)
    }

    private fun app1(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.ISO_8859_1)
        val length = bytes.size + 2
        return byteArrayOf(
            0xff.toByte(),
            0xe1.toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + bytes
    }

    private fun jpeg(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
            segments.fold(ByteArray(0)) { all, segment -> all + segment } +
            byteArrayOf(0xff.toByte(), 0xda.toByte(), 0x00, 0x02, 0xff.toByte(), 0xd9.toByte())
}
