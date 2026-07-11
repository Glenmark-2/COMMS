package com.ridecompanion.core.network.transport

import kotlinx.coroutines.flow.StateFlow

enum class TransportType {
    CELLULAR_LIVEKIT,
    LOCAL_P2P
}

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class PacketType {
    AUDIO,
    LOCATION,
    ROUTE,
    SOS
}

/** A rider joining or leaving the session, on any transport. */
data class RiderPresenceEvent(
    val riderName: String,
    val joined: Boolean
)

data class DataPacket(
    val senderId: String,
    val sessionId: String,
    val packetType: PacketType,
    val payload: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    // True when this packet was relayed from the other transport by a bridging
    // rider. Forwarded packets must never be forwarded again (loop guard).
    val forwarded: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DataPacket
        if (senderId != other.senderId) return false
        if (sessionId != other.sessionId) return false
        if (packetType != other.packetType) return false
        if (!payload.contentEquals(other.payload)) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + packetType.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

interface PayloadListener {
    fun onAudioFrameReceived(senderId: String, frame: ByteArray)
    fun onDataPacketReceived(packet: DataPacket)
}

interface CommunicationTransport {
    val transportType: TransportType
    val state: StateFlow<TransportState>
    
    fun start(sessionId: String, connectionToken: String, signalingUrl: String)
    fun stop()
    
    fun sendAudioFrame(frame: ByteArray)
    fun sendDataPacket(packet: DataPacket)
    
    fun registerPayloadListener(listener: PayloadListener)
    fun unregisterPayloadListener()
}
