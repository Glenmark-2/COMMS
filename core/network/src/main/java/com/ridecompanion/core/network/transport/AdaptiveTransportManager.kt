package com.ridecompanion.core.network.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs BOTH transports for the whole session so switching is seamless:
 *  - LiveKit (cloud, over any internet) is preferred whenever it is connected.
 *  - Nearby Connections (Bluetooth/Wi-Fi Direct mesh) stays up as a hot
 *    fallback, so the moment internet drops there is already a P2P link —
 *    no discovery gap, no dead air.
 *
 * Data packets are sent on every connected transport, and a rider who has
 * both links bridges packets between them (guarded by the `forwarded` flag
 * so nothing loops). Voice flows over LiveKit when online and over the
 * Nearby PCM stream when offline.
 */
@Singleton
class AdaptiveTransportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveKitTransport: LiveKitTransport,
    private val nearbyTransport: NearbyConnectionsTransport
) {

    private val _activeTransport = MutableStateFlow<TransportType?>(null)
    val activeTransport: StateFlow<TransportType?> = _activeTransport

    private val _incomingPackets = MutableSharedFlow<DataPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<DataPacket> = _incomingPackets

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Riders currently speaking, on either transport (cloud events + P2P VAD). */
    val activeSpeakers: StateFlow<List<String>> =
        combine(liveKitTransport.activeSpeakers, nearbyTransport.activeSpeakers) { cloud, p2p ->
            (cloud + p2p).distinct()
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Riders joining/leaving the session on any transport. A rider reachable
     * over both links would fire twice, so identical events within a short
     * window are deduplicated.
     */
    private val _riderEvents = MutableSharedFlow<RiderPresenceEvent>(extraBufferCapacity = 16)
    val riderEvents: SharedFlow<RiderPresenceEvent> = _riderEvents

    private val recentPresenceEvents = mutableMapOf<String, Long>()

    private fun emitPresence(name: String, joined: Boolean) {
        if (name.isBlank()) return
        val key = "$name:$joined"
        val now = System.currentTimeMillis()
        synchronized(recentPresenceEvents) {
            val last = recentPresenceEvents[key] ?: 0L
            if (now - last < PRESENCE_DEDUP_MILLIS) return
            recentPresenceEvents[key] = now
            recentPresenceEvents.entries.removeAll { now - it.value > PRESENCE_DEDUP_MILLIS }
        }
        _riderEvents.tryEmit(RiderPresenceEvent(name, joined))
    }

    private var isStarted = false
    private var currentSessionId: String = ""
    private var currentConnectionToken: String = ""
    private var currentSignalingUrl: String = ""

    /** This rider's display name — also their identity on both transports. */
    @Volatile var localRiderName: String = "Rider"
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            // Internet is (back) — make sure the cloud transport is connecting.
            if (isStarted) {
                liveKitTransport.start(currentSessionId, currentConnectionToken, currentSignalingUrl)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            // Only tear down the cloud link when no internet path remains. Losing
            // one network (e.g. Wi-Fi) while cellular is still up must NOT drop voice.
            if (isStarted && !isInternetAvailable()) {
                liveKitTransport.stop()
            }
        }
    }

    init {
        // Listen to each transport separately so we know where a packet came
        // from and can bridge it to the other side without echoing it back.
        liveKitTransport.registerPayloadListener(object : PayloadListener {
            override fun onAudioFrameReceived(senderId: String, frame: ByteArray) {}
            override fun onDataPacketReceived(packet: DataPacket) {
                handleIncoming(packet, from = TransportType.CELLULAR_LIVEKIT)
            }
        })
        nearbyTransport.registerPayloadListener(object : PayloadListener {
            override fun onAudioFrameReceived(senderId: String, frame: ByteArray) {
                // Played back inside NearbyConnectionsTransport itself.
            }
            override fun onDataPacketReceived(packet: DataPacket) {
                handleIncoming(packet, from = TransportType.LOCAL_P2P)
            }
        })

        // The "active" transport (shown in the UI) is cloud when connected,
        // otherwise P2P when connected, otherwise nothing yet.
        scope.launch {
            combine(liveKitTransport.state, nearbyTransport.state) { cloud, p2p ->
                when {
                    cloud == TransportState.CONNECTED -> TransportType.CELLULAR_LIVEKIT
                    p2p == TransportState.CONNECTED -> TransportType.LOCAL_P2P
                    else -> null
                }
            }.collect { _activeTransport.value = it }
        }

        // Presence events from both transports feed one deduplicated stream.
        scope.launch { liveKitTransport.participantJoined.collect { emitPresence(it, joined = true) } }
        scope.launch { liveKitTransport.participantLeft.collect { emitPresence(it, joined = false) } }
        scope.launch { nearbyTransport.riderJoined.collect { emitPresence(it, joined = true) } }
        scope.launch { nearbyTransport.riderLeft.collect { emitPresence(it, joined = false) } }
    }

    fun start(sessionId: String, connectionToken: String, signalingUrl: String) {
        if (isStarted) return
        isStarted = true
        currentSessionId = sessionId
        currentConnectionToken = connectionToken
        currentSignalingUrl = signalingUrl

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // P2P mesh always runs during a session — it's the safety net.
        nearbyTransport.start(sessionId, "", "")
        if (isInternetAvailable()) {
            liveKitTransport.start(sessionId, connectionToken, signalingUrl)
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        liveKitTransport.stop()
        nearbyTransport.stop()
        _activeTransport.value = null
    }

    /** The rider's display name, used as the P2P identity. */
    fun setLocalRiderName(name: String) {
        if (name.isNotBlank()) {
            localRiderName = name
            nearbyTransport.setLocalRiderName(name)
        }
    }

    private fun isInternetAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Mute/unmute the local microphone on every voice path. */
    fun setMicEnabled(enabled: Boolean) {
        liveKitTransport.setMicrophoneEnabled(enabled)
        nearbyTransport.setMicEnabled(enabled)
    }

    /** Scale remote rider playback volume (0f..1f) — manual slider and nav ducking. */
    fun setRemoteVolume(scale: Float) {
        liveKitTransport.setRemoteVolume(scale)
        nearbyTransport.setPlaybackVolume(scale)
    }

    /**
     * Send on every connected transport so mixed-connectivity groups stay in
     * sync (one rider offline in a dead zone still hears about routes and SOS).
     */
    fun sendData(packet: DataPacket) {
        scope.launch {
            if (liveKitTransport.state.value == TransportState.CONNECTED) {
                liveKitTransport.sendDataPacket(packet)
            }
            if (nearbyTransport.state.value == TransportState.CONNECTED) {
                nearbyTransport.sendDataPacket(packet)
            }
        }
    }

    private fun handleIncoming(packet: DataPacket, from: TransportType) {
        // A bridged copy of our own packet can come back around — drop it.
        // The original sender's name travels inside the payload ("sender").
        val originalSender = senderOf(packet)
        if (originalSender == localRiderName) return

        scope.launch {
            _incomingPackets.emit(packet.copy(senderId = originalSender))
        }
        // Bridge to the other transport exactly once, so a rider with internet
        // relays for a rider without it. `forwarded` stops relay loops.
        if (packet.forwarded) return
        val relayed = packet.copy(forwarded = true)
        when (from) {
            TransportType.CELLULAR_LIVEKIT ->
                if (nearbyTransport.state.value == TransportState.CONNECTED) {
                    nearbyTransport.sendDataPacket(relayed)
                }
            TransportType.LOCAL_P2P ->
                if (liveKitTransport.state.value == TransportState.CONNECTED) {
                    liveKitTransport.sendDataPacket(relayed)
                }
        }
    }

    /**
     * The original sender of a packet. Transport-level sender attribution is
     * wrong for bridged packets (the relaying rider appears as the sender), so
     * senders embed their name in the JSON payload as "sender".
     */
    private fun senderOf(packet: DataPacket): String {
        return try {
            val embedded = org.json.JSONObject(String(packet.payload, Charsets.UTF_8))
                .optString("sender", "")
            embedded.ifBlank { packet.senderId }
        } catch (e: Exception) {
            packet.senderId
        }
    }

    companion object {
        // A rider connecting over cloud AND mesh (or a LiveKit reconnect wave)
        // shouldn't be announced twice.
        private const val PRESENCE_DEDUP_MILLIS = 30_000L
    }
}
