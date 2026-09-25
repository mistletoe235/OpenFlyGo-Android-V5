package edu.playground.djivln.reconstruction

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V86PlyValidationTest {
    @Test fun emptyPointCloudHasActionableErrorForBothInputs() {
        checkRejected(header(0).toByteArray(), "PLY contains no vertices")
    }

    @Test fun oversizedHeaderIsRejectedForBothInputs() {
        val comments = "comment bounded line\n".repeat(4_000)
        val bytes = header(1).replace("end_header", comments + "end_header").toByteArray() + ByteArray(15)
        checkRejected(bytes, "PLY header exceeds size limit")
    }

    @Test fun oversizedHeaderLineIsRejectedWithoutReadingWholeFile() {
        val bytes = header(1).replace("end_header", "comment " + "x".repeat(5_000) + "\nend_header")
            .toByteArray() + ByteArray(15)
        val file = Files.createTempFile("v86-long-header", ".ply").toFile()
        try {
            file.writeBytes(bytes)
            val error = runCatching { V86PlyDecoder.decode(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("PLY header line exceeds size limit", error?.message)
        } finally {
            file.delete()
        }
    }

    private fun checkRejected(bytes: ByteArray, message: String) {
        val memoryError = runCatching { V86PlyDecoder.decode(bytes) }.exceptionOrNull()
        assertTrue(memoryError is IllegalArgumentException)
        assertEquals(message, memoryError?.message)
        val file = Files.createTempFile("v86-invalid-header", ".ply").toFile()
        try {
            file.writeBytes(bytes)
            val fileError = runCatching { V86PlyDecoder.decode(file) }.exceptionOrNull()
            assertTrue(fileError is IllegalArgumentException)
            assertEquals(message, fileError?.message)
        } finally {
            file.delete()
        }
    }

    private fun header(count: Int) = """
        ply
        format binary_little_endian 1.0
        element vertex $count
        property float x
        property float y
        property float z
        property uchar red
        property uchar green
        property uchar blue
        end_header
    """.trimIndent() + "\n"
}
