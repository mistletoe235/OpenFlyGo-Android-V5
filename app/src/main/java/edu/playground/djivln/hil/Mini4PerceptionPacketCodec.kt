package edu.playground.djivln.hil

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payload carried by DJI perception cmdset 0x24 / cmdid 0x08 on Mini 4 Pro.
 * This codec is deliberately transport-free: it cannot send a packet to the aircraft.
 */
object Mini4PerceptionPacketCodec {
    const val COMMAND_SET = 0x24
    const val COMMAND_ID = 0x08
    const val INVALID_DISTANCE_MILLIMETERS = 60_000
    const val MAX_HORIZONTAL_SAMPLES = 360
    const val HEADER_BYTES = 8

    data class Observation(
        val status: Int,
        val upwardDistanceMillimeters: Int,
        val downwardDistanceMillimeters: Int,
        val horizontalDistanceMillimeters: List<Int>,
    )

    fun encode(observation: Observation): ByteArray {
        require(observation.status in 0..0xffff)
        requireDistance(observation.upwardDistanceMillimeters)
        requireDistance(observation.downwardDistanceMillimeters)
        require(observation.horizontalDistanceMillimeters.size in 1..MAX_HORIZONTAL_SAMPLES)
        observation.horizontalDistanceMillimeters.forEach(::requireDistance)
        return ByteBuffer.allocate(HEADER_BYTES + observation.horizontalDistanceMillimeters.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(observation.status.toShort())
            .putShort(observation.upwardDistanceMillimeters.toShort())
            .putShort(observation.downwardDistanceMillimeters.toShort())
            .putShort(observation.horizontalDistanceMillimeters.size.toShort())
            .apply { observation.horizontalDistanceMillimeters.forEach { putShort(it.toShort()) } }
            .array()
    }

    fun decode(payload: ByteArray): Observation {
        require(payload.size >= HEADER_BYTES)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val status = buffer.short.toInt() and 0xffff
        val upward = buffer.short.toInt() and 0xffff
        val downward = buffer.short.toInt() and 0xffff
        val count = buffer.short.toInt() and 0xffff
        require(count in 1..MAX_HORIZONTAL_SAMPLES)
        require(payload.size == HEADER_BYTES + count * 2)
        val horizontal = List(count) { buffer.short.toInt() and 0xffff }
        return Observation(status, upward, downward, horizontal)
    }

    private fun requireDistance(value: Int) {
        require(value in 0..INVALID_DISTANCE_MILLIMETERS)
    }
}
