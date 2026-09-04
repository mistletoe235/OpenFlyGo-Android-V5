package edu.playground.djivln.survey

import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.Rasters
import mil.nga.tiff.TIFFImage
import mil.nga.tiff.TiffWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import kotlin.concurrent.thread

class GlobalBuildingHeightDownloaderStaticTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `downloads one static tile then reuses local cache`() {
        val bytes = heightTileBytes()
        val server = ServerSocket(0)
        val serving = thread(name = "static-cog-fixture") {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().apply {
                    write((
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: image/tiff\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "ETag: test-v1\r\nConnection: close\r\n\r\n"
                        ).toByteArray())
                    write(bytes)
                    flush()
                }
            }
            server.close()
        }
        val roi = listOf(
            GeoPoint(31.02, 121.42), GeoPoint(31.02, 121.44),
            GeoPoint(31.04, 121.44), GeoPoint(31.04, 121.42),
        )
        val downloader = GlobalBuildingHeightDownloader(
            temporaryFolder.newFolder("cache"),
            "http://127.0.0.1:${server.localPort}/{x}/{y}.tif",
        )

        val first = downloader.download(roi)
        serving.join(5_000)
        assertFalse(serving.isAlive)
        assertFalse(first.cached)
        assertEquals(1, first.tileCount)
        assertEquals(12.0, first.heightAboveGround.elevationMeters(31.03, 121.43), 1e-5)

        val second = downloader.download(roi)
        assertTrue(second.cached)
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.bytes, second.bytes)
    }

    private fun heightTileBytes(): ByteArray {
        val rasters = Rasters(2, 2, 1, FieldType.FLOAT)
        repeat(2) { y -> repeat(2) { x -> rasters.setFirstPixelSample(x, y, 12f) } }
        val directory = FileDirectory(rasters)
        directory.setImageWidth(2)
        directory.setImageHeight(2)
        directory.setBitsPerSample(32)
        directory.setSamplesPerPixel(1)
        directory.setSampleFormat(3)
        directory.setPlanarConfiguration(1)
        directory.setRowsPerStrip(2)
        directory.setCompression(8)
        directory.setPhotometricInterpretation(1)
        directory.setModelPixelScale(listOf(0.1, 0.1, 0.0))
        directory.setModelTiepoint(listOf(0.0, 0.0, 0.0, 121.4, 31.2, 0.0))
        directory.setUnsignedIntegerListEntryValue(
            FieldTagType.GeoKeyDirectory,
            listOf(1, 1, 0, 1, 2048, 0, 1, 4326),
        )
        return TiffWriter.writeTiffToBytes(TIFFImage(directory))
    }
}
