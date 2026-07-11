package com.ridecompanion.core.network.transport

import android.content.Context
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.RemoteAudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LiveKitTransport"

// Suffix appended to the data topic when a packet is a bridge-forward,
// so receivers never forward it a second time (loop guard).
private const val FORWARDED_SUFFIX = ":F"

class LiveKitTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : CommunicationTransport {

    override val transportType = TransportType.CELLULAR_LIVEKIT

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state

    // Identities of participants currently speaking (excludes the local rider).
    private val _activeSpeakers = MutableStateFlow<List<String>>(emptyList())
    val activeSpeakers: StateFlow<List<String>> = _activeSpeakers

    // Rider names joining/leaving the room — route re-share + voice announcements.
    private val _participantJoined = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val participantJoined: SharedFlow<String> = _participantJoined

    private val _participantLeft = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val participantLeft: SharedFlow<String> = _participantLeft

    private var room: Room? = null
    private var payloadListener: PayloadListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Remote audio tracks we control the playback volume of (slider + nav ducking).
    private val remoteAudioTracks = mutableSetOf<RemoteAudioTrack>()
    private var currentVolume = 1.0

    // Desired state that must survive reconnects.
    @Volatile private var shouldBeConnected = false
    @Volatile private var micDesiredEnabled = true
    private var connectJob: Job? = null

    private var sessionId: String = ""
    private var token: String = ""
    private var url: String = ""

    override fun start(sessionId: String, connectionToken: String, signalingUrl: String) {
        if (shouldBeConnected) return
        shouldBeConnected = true
        this.sessionId = sessionId
        this.token = connectionToken
        this.url = signalingUrl
        connectWithRetry()
    }

    private fun connectWithRetry() {
        connectJob?.cancel()
        connectJob = scope.launch {
            var attempt = 0
            while (shouldBeConnected && _state.value != TransportState.CONNECTED) {
                attempt++
                // Tear down any half-dead room from a previous attempt first.
                room?.let { old ->
                    room = null
                    runCatching { old.disconnect() }
                }
                _state.value = TransportState.CONNECTING
                Log.d(TAG, "Connecting to LiveKit (attempt $attempt): url=$url")
                try {
                    connectOnce()
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "LiveKit connect failed: ${e.message}")
                    _state.value = TransportState.DISCONNECTED
                    // Backoff: 2s, 4s, 8s, capped at 15s. Retries forever while the
                    // session wants a connection — the network callback also re-kicks us.
                    val backoffMs = minOf(2000L * (1L shl minOf(attempt - 1, 3)), 15000L)
                    delay(backoffMs)
                }
            }
        }
    }

    private suspend fun connectOnce() {
        val newRoom = LiveKit.create(context)
        room = newRoom

        scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.d(TAG, "Connected to LiveKit room")
                        _state.value = TransportState.CONNECTED
                        scope.launch {
                            try {
                                // Respect the rider's mute state across reconnects.
                                newRoom.localParticipant.setMicrophoneEnabled(micDesiredEnabled)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set mic: ${e.message}")
                            }
                        }
                    }
                    is RoomEvent.FailedToConnect -> {
                        Log.e(TAG, "Failed to connect to LiveKit")
                        // Ignore events from rooms we already replaced/abandoned.
                        if (room === newRoom) {
                            _state.value = TransportState.DISCONNECTED
                            if (shouldBeConnected) connectWithRetry()
                        }
                    }
                    is RoomEvent.Disconnected -> {
                        Log.d(TAG, "Disconnected from LiveKit: ${event.reason}")
                        // LiveKit's internal resume handles brief blips; if it gave up
                        // and we still want to be online, rebuild the connection.
                        if (room === newRoom) {
                            _state.value = TransportState.DISCONNECTED
                            if (shouldBeConnected) connectWithRetry()
                        }
                    }
                    is RoomEvent.TrackSubscribed -> {
                        Log.d(TAG, "Subscribed to track from ${event.participant.identity?.value}")
                        val track = event.track
                        if (track is RemoteAudioTrack) {
                            synchronized(remoteAudioTracks) { remoteAudioTracks.add(track) }
                            // Apply the current intercom volume to the newly joined rider.
                            runCatching { track.setVolume(currentVolume) }
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        val track = event.track
                        if (track is RemoteAudioTrack) {
                            synchronized(remoteAudioTracks) { remoteAudioTracks.remove(track) }
                        }
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        val localId = newRoom.localParticipant.identity?.value
                        _activeSpeakers.value = event.speakers
                            .mapNotNull { it.identity?.value }
                            .filter { it != localId }
                    }
                    is RoomEvent.DataReceived -> {
                        val senderId = event.participant?.identity?.value ?: "unknown"
                        val data = event.data
                        // The packet type travels in the LiveKit data "topic".
                        val rawTopic = event.topic ?: "LOCATION"
                        val forwarded = rawTopic.endsWith(FORWARDED_SUFFIX)
                        val typeName = rawTopic.removeSuffix(FORWARDED_SUFFIX)
                        val type = runCatching { PacketType.valueOf(typeName) }
                            .getOrDefault(PacketType.LOCATION)
                        val packet = DataPacket(
                            senderId = senderId,
                            sessionId = sessionId,
                            packetType = type,
                            payload = data,
                            forwarded = forwarded
                        )
                        payloadListener?.onDataPacketReceived(packet)
                    }
                    is RoomEvent.ParticipantConnected -> {
                        Log.d(TAG, "Participant joined: ${event.participant.identity?.value}")
                        _participantJoined.tryEmit(event.participant.identity?.value ?: "A rider")
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        Log.d(TAG, "Participant left: ${event.participant.identity?.value}")
                        _participantLeft.tryEmit(event.participant.identity?.value ?: "A rider")
                    }
                    else -> {}
                }
            }
        }

        newRoom.connect(url, token)
    }

    override fun stop() {
        shouldBeConnected = false
        connectJob?.cancel()
        connectJob = null
        _state.value = TransportState.DISCONNECTED
        val oldRoom = room
        room = null
        synchronized(remoteAudioTracks) { remoteAudioTracks.clear() }
        _activeSpeakers.value = emptyList()
        scope.launch {
            runCatching { oldRoom?.disconnect() }
        }
    }

    /** Enable or disable the local microphone (intercom mute/unmute). */
    fun setMicrophoneEnabled(enabled: Boolean) {
        micDesiredEnabled = enabled
        scope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(enabled)
                Log.d(TAG, "Microphone ${if (enabled) "enabled" else "muted"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set microphone enabled=$enabled: ${e.message}")
            }
        }
    }

    /** Scale the playback volume (0f..1f) of every remote rider's voice. */
    fun setRemoteVolume(scale: Float) {
        currentVolume = scale.coerceIn(0f, 1f).toDouble()
        val tracks = synchronized(remoteAudioTracks) { remoteAudioTracks.toList() }
        tracks.forEach { track ->
            runCatching { track.setVolume(currentVolume) }
        }
    }

    override fun sendAudioFrame(frame: ByteArray) {
        // Automatically handled by local mic track in LiveKit
    }

    override fun sendDataPacket(packet: DataPacket) {
        scope.launch {
            try {
                // Telemetry is frequent and disposable (LOSSY); routes and SOS must
                // arrive, so they use RELIABLE delivery. The type rides in the topic.
                val reliability = if (packet.packetType == PacketType.LOCATION) {
                    DataPublishReliability.LOSSY
                } else {
                    DataPublishReliability.RELIABLE
                }
                val topic = packet.packetType.name +
                    if (packet.forwarded) FORWARDED_SUFFIX else ""
                room?.localParticipant?.publishData(
                    data = packet.payload,
                    reliability = reliability,
                    topic = topic
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
