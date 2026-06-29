package com.ridecompanion.core.network.transport

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.DataPublishReliability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LiveKitTransport"

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
        Log.d(TAG, "Connecting to LiveKit: url=$signalingUrl")
        
        scope.launch {
            try {
                val newRoom = LiveKit.create(context)
                room = newRoom
                
                // Collect events using the correct LiveKit 2.x API
                scope.launch {
                    newRoom.events.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                Log.d(TAG, "Connected to LiveKit room")
                                _state.value = TransportState.CONNECTED
                                scope.launch {
                                    try {
                                        newRoom.localParticipant.setMicrophoneEnabled(true)
                                        Log.d(TAG, "Microphone enabled")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to enable mic: ${e.message}")
                                    }
                                }
                            }
                            is RoomEvent.FailedToConnect -> {
                                Log.e(TAG, "Failed to connect to LiveKit")
                                _state.value = TransportState.DISCONNECTED
                            }
                            is RoomEvent.Disconnected -> {
                                Log.d(TAG, "Disconnected from LiveKit")
                                _state.value = TransportState.DISCONNECTED
                            }
                            is RoomEvent.TrackSubscribed -> {
                                Log.d(TAG, "Subscribed to track from ${event.participant.identity?.value}")
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
                Log.e(TAG, "LiveKit connection error: ${e.message}", e)
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
            try {
                room?.localParticipant?.publishData(
                    data = packet.payload,
                    reliability = DataPublishReliability.LOSSY
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish data: ${e.message}")
            }
        }
    }

    override fun registerPayloadListener(listener: PayloadListener) {
        this.payloadListener = listener
    }

    override fun unregisterPayloadListener() {
        this.payloadListener = null
    }
}
