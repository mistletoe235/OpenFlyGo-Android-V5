package edu.playground.djivln.hil

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Binary, queue-free control-plane protocol used between Android and the UE HIL adapter. */
object HilProtocol {
    const val MAGIC: Int = 0x4f46484c // OFHL
    const val VERSION: Short = 1
    const val HEADER_BYTES = 32
    const val MAX_DATAGRAM_BYTES = 1_400

    const val TYPE_HELLO: Short = 1
    const val TYPE_POSE: Short = 2
    const val TYPE_HEARTBEAT: Short = 3
    const val TYPE_PING: Short = 4
    const val TYPE_PONG: Short = 5
    const val TYPE_EVENT: Short = 6

    const val POSE_FLAG_MOTORS_ON = 1
    const val POSE_FLAG_FLYING = 1 shl 1
    const val POSE_FLAG_VIRTUAL_STICK = 1 shl 2

    const val EVENT_INFO = 0
    const val EVENT_COLLISION = 1
    const val EVENT_STOP = 2
    const val EVENT_EMERGENCY = 3

    data class Header(
        val type: Short,
        val flags: Int,
        val sessionId: Long,
        val sequence: Long,
        val payloadBytes: Int,
    )

    data class Datagram(val header: Header, val payload: ByteBuffer)

    data class Hello(
        val monotonicNanos: Long,
        val requestedPoseHz: Int,
        val simulatorStateHz: Int,
        val frameTcpPort: Int,
        val capabilities: Int,
    )

    data class Pose(
        val sampleMonotonicNanos: Long,
        val originLatitudeDegrees: Double,
        val originLongitudeDegrees: Double,
        val eastMeters: Double,
        val northMeters: Double,
        val upMeters: Double,
        val rollDegrees: Double,
        val pitchDegrees: Double,
        val headingDegreesClockwiseFromNorth: Double,
        val velocityNorthMetersPerSecond: Double,
        val velocityEastMetersPerSecond: Double,
        val velocityUpMetersPerSecond: Double,
        val gimbalPitchDegrees: Double,
        val commandForwardMetersPerSecond: Double,
        val commandRightMetersPerSecond: Double,
        val commandUpMetersPerSecond: Double,
        val commandYawRateDegreesPerSecond: Double,
        val flightStateAgeMillis: Int,
        val measuredSimulatorHz: Float,
        val stateFlags: Int,
    )

    data class Heartbeat(
        val monotonicNanos: Long,
        val lastReceivedSequence: Long,
        val stateFlags: Int,
    )

    data class Ping(val senderMonotonicNanos: Long)

    data class Pong(
        val echoedSenderMonotonicNanos: Long,
        val peerMonotonicNanos: Long,
    )

    data class Event(
        val peerMonotonicNanos: Long,
        val kind: Int,
        val stopScore: Double,
        val poseSequence: Long,
        val reason: String,
    )

    fun encodeHello(sessionId: Long, sequence: Long, value: Hello): ByteArray = encode(
        TYPE_HELLO, 0, sessionId, sequence, 28,
    ) { buffer ->
        buffer.putLong(value.monotonicNanos)
        buffer.putInt(value.requestedPoseHz)
        buffer.putInt(value.simulatorStateHz)
        buffer.putInt(value.frameTcpPort)
        buffer.putInt(value.capabilities)
        buffer.putInt(0)
    }

    fun encodePose(sessionId: Long, sequence: Long, value: Pose): ByteArray = encode(
        TYPE_POSE, 0, sessionId, sequence, POSE_PAYLOAD_BYTES,
    ) { buffer ->
        buffer.putLong(value.sampleMonotonicNanos)
        buffer.putDouble(value.originLatitudeDegrees)
        buffer.putDouble(value.originLongitudeDegrees)
        buffer.putDouble(value.eastMeters)
        buffer.putDouble(value.northMeters)
        buffer.putDouble(value.upMeters)
        buffer.putDouble(value.rollDegrees)
        buffer.putDouble(value.pitchDegrees)
        buffer.putDouble(value.headingDegreesClockwiseFromNorth)
        buffer.putDouble(value.velocityNorthMetersPerSecond)
        buffer.putDouble(value.velocityEastMetersPerSecond)
        buffer.putDouble(value.velocityUpMetersPerSecond)
        buffer.putDouble(value.gimbalPitchDegrees)
        buffer.putDouble(value.commandForwardMetersPerSecond)
        buffer.putDouble(value.commandRightMetersPerSecond)
        buffer.putDouble(value.commandUpMetersPerSecond)
        buffer.putDouble(value.commandYawRateDegreesPerSecond)
        buffer.putInt(value.flightStateAgeMillis)
        buffer.putFloat(value.measuredSimulatorHz)
        buffer.putInt(value.stateFlags)
        buffer.putInt(0)
    }

    fun encodeHeartbeat(sessionId: Long, sequence: Long, value: Heartbeat): ByteArray = encode(
        TYPE_HEARTBEAT, 0, sessionId, sequence, 24,
    ) { buffer ->
        buffer.putLong(value.monotonicNanos)
        buffer.putLong(value.lastReceivedSequence)
        buffer.putInt(value.stateFlags)
        buffer.putInt(0)
    }

    fun encodePing(sessionId: Long, sequence: Long, value: Ping): ByteArray = encode(
        TYPE_PING, 0, sessionId, sequence, 8,
    ) { it.putLong(value.senderMonotonicNanos) }

    fun encodePong(sessionId: Long, sequence: Long, value: Pong): ByteArray = encode(
        TYPE_PONG, 0, sessionId, sequence, 16,
    ) { buffer ->
        buffer.putLong(value.echoedSenderMonotonicNanos)
        buffer.putLong(value.peerMonotonicNanos)
    }

    fun encodeEvent(sessionId: Long, sequence: Long, value: Event): ByteArray {
        val reasonBytes = value.reason.toByteArray(Charsets.UTF_8)
        require(reasonBytes.size <= MAX_EVENT_REASON_BYTES) { "event reason is too long" }
        return encode(TYPE_EVENT, 0, sessionId, sequence, 32 + reasonBytes.size) { buffer ->
            buffer.putLong(value.peerMonotonicNanos)
            buffer.putInt(value.kind)
            buffer.putDouble(value.stopScore)
            buffer.putLong(value.poseSequence)
            buffer.putInt(reasonBytes.size)
            buffer.put(reasonBytes)
        }
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size): Datagram {
        require(length in HEADER_BYTES..minOf(bytes.size, MAX_DATAGRAM_BYTES)) { "invalid datagram length" }
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "invalid HIL magic" }
        require(buffer.short == VERSION) { "unsupported HIL version" }
        val type = buffer.short
        require(type in TYPE_HELLO..TYPE_EVENT) { "invalid HIL type $type" }
        val flags = buffer.int
        val sessionId = buffer.long
        val sequence = buffer.long
        val payloadBytes = buffer.int
        require(payloadBytes >= 0 && payloadBytes == buffer.remaining()) { "invalid HIL payload length" }
        return Datagram(Header(type, flags, sessionId, sequence, payloadBytes), buffer.slice().order(ByteOrder.BIG_ENDIAN))
    }

    fun decodeHello(payload: ByteBuffer): Hello {
        requirePayload(payload, 28)
        return Hello(payload.long, payload.int, payload.int, payload.int, payload.int).also { payload.int }
    }

    fun decodePose(payload: ByteBuffer): Pose {
        requirePayload(payload, POSE_PAYLOAD_BYTES)
        return Pose(
            sampleMonotonicNanos = payload.long,
            originLatitudeDegrees = payload.double,
            originLongitudeDegrees = payload.double,
            eastMeters = payload.double,
            northMeters = payload.double,
            upMeters = payload.double,
            rollDegrees = payload.double,
            pitchDegrees = payload.double,
            headingDegreesClockwiseFromNorth = payload.double,
            velocityNorthMetersPerSecond = payload.double,
            velocityEastMetersPerSecond = payload.double,
            velocityUpMetersPerSecond = payload.double,
            gimbalPitchDegrees = payload.double,
            commandForwardMetersPerSecond = payload.double,
            commandRightMetersPerSecond = payload.double,
            commandUpMetersPerSecond = payload.double,
            commandYawRateDegreesPerSecond = payload.double,
            flightStateAgeMillis = payload.int,
            measuredSimulatorHz = payload.float,
            stateFlags = payload.int,
        ).also { payload.int }
    }

    fun decodeHeartbeat(payload: ByteBuffer): Heartbeat {
        requirePayload(payload, 24)
        return Heartbeat(payload.long, payload.long, payload.int).also { payload.int }
    }

    fun decodePing(payload: ByteBuffer): Ping {
        requirePayload(payload, 8)
        return Ping(payload.long)
    }

    fun decodePong(payload: ByteBuffer): Pong {
        requirePayload(payload, 16)
        return Pong(payload.long, payload.long)
    }

    fun decodeEvent(payload: ByteBuffer): Event {
        require(payload.remaining() >= 32) { "invalid event payload" }
        val peerNanos = payload.long
        val kind = payload.int
        require(kind in EVENT_INFO..EVENT_EMERGENCY) { "invalid event kind $kind" }
        val stopScore = payload.double
        require(stopScore.isFinite() || stopScore.isNaN()) { "invalid event stop score" }
        val poseSequence = payload.long
        val reasonBytes = payload.int
        require(reasonBytes in 0..MAX_EVENT_REASON_BYTES && reasonBytes == payload.remaining()) {
            "invalid event reason length"
        }
        val encoded = ByteArray(reasonBytes)
        payload.get(encoded)
        val reason = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
        return Event(peerNanos, kind, stopScore, poseSequence, reason)
    }

    private inline fun encode(
        type: Short,
        flags: Int,
        sessionId: Long,
        sequence: Long,
        payloadBytes: Int,
        writePayload: (ByteBuffer) -> Unit,
    ): ByteArray {
        require(payloadBytes + HEADER_BYTES <= MAX_DATAGRAM_BYTES)
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payloadBytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION)
        buffer.putShort(type)
        buffer.putInt(flags)
        buffer.putLong(sessionId)
        buffer.putLong(sequence)
        buffer.putInt(payloadBytes)
        writePayload(buffer)
        check(!buffer.hasRemaining()) { "HIL encoder payload size mismatch" }
        return buffer.array()
    }

    private fun requirePayload(payload: ByteBuffer, expected: Int) {
        require(payload.remaining() == expected) { "invalid payload length ${payload.remaining()}, expected $expected" }
    }

    private const val POSE_PAYLOAD_BYTES = 152
    private const val MAX_EVENT_REASON_BYTES = 512
}
