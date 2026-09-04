package edu.playground.djivln.hil

internal sealed interface HilInboundMessage {
    data class Heartbeat(val value: HilProtocol.Heartbeat) : HilInboundMessage
    data class Ping(val value: HilProtocol.Ping) : HilInboundMessage
    data class Pong(val value: HilProtocol.Pong) : HilInboundMessage
    data class Event(val value: HilProtocol.Event) : HilInboundMessage
}

internal object HilInboundMessageDecoder {
    fun decode(datagram: HilProtocol.Datagram): HilInboundMessage {
        require(datagram.header.flags == 0) { "unsupported HIL header flags" }
        return when (datagram.header.type) {
            HilProtocol.TYPE_HEARTBEAT -> HilInboundMessage.Heartbeat(HilProtocol.decodeHeartbeat(datagram.payload))
            HilProtocol.TYPE_PING -> HilInboundMessage.Ping(HilProtocol.decodePing(datagram.payload))
            HilProtocol.TYPE_PONG -> HilInboundMessage.Pong(HilProtocol.decodePong(datagram.payload))
            HilProtocol.TYPE_EVENT -> HilInboundMessage.Event(HilProtocol.decodeEvent(datagram.payload))
            else -> throw IllegalArgumentException("unexpected inbound HIL type ${datagram.header.type}")
        }
    }
}
