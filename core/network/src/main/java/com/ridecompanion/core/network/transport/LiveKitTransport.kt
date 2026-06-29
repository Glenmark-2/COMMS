package com.ridecompanion.core.network.transport

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.LocalParticipant
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

class LiveKitTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : CommunicationTransport {

    override val transportType = TransportType.CELLULAR_LIVEKIT
    
    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state

    private var room: Room? = null
    private var payloadListener: PayloadListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun start(sessionId: String, connectionToken: String, signalingUrl: String) {
        if (_state.value != TransportState.DISCONNECTED) return
        
        _state.value = TransportState.CONNECTING
        scope.launch {
            try {
                val newRoom = LiveKit.create(context)
                room = newRoom
                
                scope.launch {
                    @Suppress("UNCHECKED_CAST")
                    val eventsFlow = newRoom.events as Flow<RoomEvent>
                    eventsFlow.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                _state.value = TransportState.CONNECTED
                                scope.launch {
                                    newRoom.localParticipant.setMicrophoneEnabled(true)
                                }
                            }
                            is RoomEvent.FailedToConnect -> {
                                _state.value = TransportState.DISCONNECTED
                            }
                            is RoomEvent.Disconnected -> {
                                _state.value = TransportState.DISCONNECTED
                            }
                            is RoomEvent.TrackSubscribed -> {
                                // Subscribed to audio track
                            }
                            is RoomEvent.DataReceived -> {
                                val senderId = event.participant?.identity?.value ?: "unknown"
                                val data = event.data
                                val packet = DataPacket(
                                    senderId = senderId,
                                    sessionId = sessionId,
                                    packetType = PacketType.LOCATION,
                                    payload = data
                                )
                                payloadListener?.onDataPacketReceived(packet)
                            }
                            else -> {}
                        }
                    }
                }

                newRoom.connect(signalingUrl, connectionToken)
                
            } catch (e: Exception) {
                _state.value = TransportState.DISCONNECTED
            }
        }
    }

    override fun stop() {
        scope.launch {
            room?.disconnect()
            room = null
            _state.value = TransportState.DISCONNECTED
        }
    }

    override fun sendAudioFrame(frame: ByteArray) {
        // Automatically handled by local mic track in LiveKit
    }

    override fun sendDataPacket(packet: DataPacket) {
        scope.launch {
            room?.localParticipant?.publishData(
                data = packet.payload,
                reliability = DataPublishReliability.LOSSY
            )
        }
    }

    override fun registerPayloadListener(listener: PayloadListener) {
        this.payloadListener = listener
    }

    override fun unregisterPayloadListener() {
        this.payloadListener = null
    }
}
