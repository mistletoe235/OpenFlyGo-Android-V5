package edu.playground.djivln.reconstruction

import android.content.Context
import edu.playground.djivln.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.RandomAccessFile

data class V86PointCloud(
    /** Interleaved local metric XYZ coordinates. */
    val xyz: FloatArray,
    val colors: IntArray,
    val candidates: List<V86Candidate> = emptyList(),
) {
    val size: Int get() = colors.size
}

object V86PlyDecoder {
    fun decode(
        file: File,
        candidates: List<V86Candidate> = emptyList(),
        maxPoints: Int = 30_000,
        context: Context? = null,
    ): V86PointCloud {
        require(file.isFile && file.length() >= 64L) {
            text(context, R.string.ply_file_missing_or_small, "PLY file is missing or too small")
        }
        RandomAccessFile(file, "r").use { input ->
            require(input.readLine() == "ply") { text(context, R.string.not_ply_file, "Not a PLY file") }
            require(input.readLine() == "format binary_little_endian 1.0") {
                text(context, R.string.ply_binary_little_endian_only, "Only binary_little_endian PLY is supported")
            }
            var vertexCount: Int? = null
            val properties = mutableListOf<List<String>>()
            var readingVertexProperties = false
            while (true) {
                val line = input.readLine() ?: error(text(context, R.string.ply_end_header_missing, "PLY is missing end_header"))
                if (line == "end_header") break
                if (line.startsWith("element vertex ")) {
                    vertexCount = line.substringAfterLast(' ').toIntOrNull()
                    readingVertexProperties = true
                } else if (line.startsWith("element ")) {
                    readingVertexProperties = false
                } else if (readingVertexProperties && line.startsWith("property ")) {
                    properties += line.trim().split(Regex("\\s+"))
                }
            }
            val count = vertexCount ?: error(text(context, R.string.ply_vertex_count_missing, "PLY is missing the vertex count"))
            require(properties == SUPPORTED_PROPERTIES) {
                text(context, R.string.ply_vertex_contract_mismatch, "PLY vertex fields do not match the V86 XYZ+RGB contract")
            }
            val dataOffset = input.filePointer
            require(dataOffset + count.toLong() * VERTEX_STRIDE <= file.length()) {
                text(context, R.string.ply_vertex_data_incomplete, "PLY vertex data is incomplete")
            }
            val targetCount = minOf(count, maxPoints.coerceAtLeast(1))
            val step = maxOf(1, (count + targetCount - 1) / targetCount)
            val capacity = (count + step - 1) / step
            val xyz = FloatArray(capacity * 3)
            val colors = IntArray(capacity)
            var output = 0
            var source = 0
            input.seek(dataOffset)
            while (source < count && output < capacity) {
                xyz[output * 3] = input.readLittleEndianFloat()
                xyz[output * 3 + 1] = input.readLittleEndianFloat()
                xyz[output * 3 + 2] = input.readLittleEndianFloat()
                val red = input.readUnsignedByte()
                val green = input.readUnsignedByte()
                val blue = input.readUnsignedByte()
                colors[output] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
                output += 1
                val skipVertices = minOf(step - 1, count - source - 1)
                if (skipVertices > 0) input.seek(input.filePointer + skipVertices.toLong() * VERTEX_STRIDE)
                source += step
            }
            return V86PointCloud(
                xyz = if (output == capacity) xyz else xyz.copyOf(output * 3),
                colors = if (output == capacity) colors else colors.copyOf(output),
                candidates = candidates,
            )
        }
    }

    fun decode(
        bytes: ByteArray,
        candidates: List<V86Candidate> = emptyList(),
        maxPoints: Int = 30_000,
        context: Context? = null,
    ): V86PointCloud {
        require(bytes.size >= 64) { text(context, R.string.ply_file_too_small, "PLY file is too small") }
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        val headerEnd = bytes.indexOf(marker)
        require(headerEnd >= 0) { text(context, R.string.ply_end_header_missing, "PLY is missing end_header") }
        val dataOffset = headerEnd + marker.size
        val header = bytes.copyOfRange(0, dataOffset).toString(Charsets.US_ASCII)
        require(header.startsWith("ply\n")) { text(context, R.string.not_ply_file, "Not a PLY file") }
        require("format binary_little_endian 1.0" in header) {
            text(context, R.string.ply_binary_little_endian_only, "Only binary_little_endian PLY is supported")
        }
        val vertexCount = Regex("element vertex (\\d+)")
            .find(header)?.groupValues?.get(1)?.toIntOrNull()
            ?: error(text(context, R.string.ply_vertex_count_missing, "PLY is missing the vertex count"))
        val properties = header.lineSequence()
            .dropWhile { !it.startsWith("element vertex ") }
            .drop(1)
            .takeWhile { !it.startsWith("element ") && it != "end_header" }
            .filter { it.startsWith("property ") }
            .map { it.trim().split(Regex("\\s+")) }
            .toList()
        require(properties == SUPPORTED_PROPERTIES) {
            text(context, R.string.ply_vertex_contract_mismatch, "PLY vertex fields do not match the V86 XYZ+RGB contract")
        }
        require(dataOffset + vertexCount.toLong() * VERTEX_STRIDE <= bytes.size.toLong()) {
            text(context, R.string.ply_vertex_data_incomplete, "PLY vertex data is incomplete")
        }
        val targetCount = minOf(vertexCount, maxPoints.coerceAtLeast(1))
        val step = maxOf(1, (vertexCount + targetCount - 1) / targetCount)
        val capacity = (vertexCount + step - 1) / step
        val xyz = FloatArray(capacity * 3)
        val colors = IntArray(capacity)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var output = 0
        var source = 0
        while (source < vertexCount && output < capacity) {
            buffer.position(dataOffset + source * VERTEX_STRIDE)
            xyz[output * 3] = buffer.float
            xyz[output * 3 + 1] = buffer.float
            xyz[output * 3 + 2] = buffer.float
            val red = buffer.get().toInt() and 0xff
            val green = buffer.get().toInt() and 0xff
            val blue = buffer.get().toInt() and 0xff
            colors[output] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
            output += 1
            source += step
        }
        return V86PointCloud(
            xyz = if (output == capacity) xyz else xyz.copyOf(output * 3),
            colors = if (output == capacity) colors else colors.copyOf(output),
            candidates = candidates,
        )
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (index in 0..size - needle.size) {
            for (offset in needle.indices) if (this[index + offset] != needle[offset]) continue@outer
            return index
        }
        return -1
    }

    private fun text(context: Context?, resourceId: Int, fallback: String): String =
        context?.getString(resourceId) ?: fallback

    private fun RandomAccessFile.readLittleEndianFloat(): Float =
        Float.fromBits(Integer.reverseBytes(readInt()))

    private const val VERTEX_STRIDE = 15
    private val SUPPORTED_PROPERTIES = listOf(
        listOf("property", "float", "x"),
        listOf("property", "float", "y"),
        listOf("property", "float", "z"),
        listOf("property", "uchar", "red"),
        listOf("property", "uchar", "green"),
        listOf("property", "uchar", "blue"),
    )
}
