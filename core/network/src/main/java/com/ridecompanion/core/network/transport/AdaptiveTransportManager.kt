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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdaptiveTransportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveKitTransport: LiveKitTransport,
    private val nearbyTransport: NearbyConnectionsTransport
) : PayloadListener {

    private val _activeTransport = MutableStateFlow<TransportType?>(null)
    val activeTransport: StateFlow<TransportType?> = _activeTransport

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var isStarted = false
    private lateinit var currentSessionId: String
    private lateinit var currentConnectionToken: String
    private lateinit var currentSignalingUrl: String

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            scope.launch {
                switchToCellular()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            scope.launch {
                switchToP2P()
            }
        }
    }

    init {
        liveKitTransport.registerPayloadListener(this)
        nearbyTransport.registerPayloadListener(this)
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

        if (isInternetAvailable()) {
            switchToCellular()
        } else {
            switchToP2P()
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        connectivityManager.unregisterNetworkCallback(networkCallback)
        liveKitTransport.stop()
        nearbyTransport.stop()
        _activeTransport.value = null
    }

    private fun isInternetAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun switchToCellular() {
        scope.launch {
            nearbyTransport.stop()
            liveKitTransport.start(currentSessionId, currentConnectionToken, currentSignalingUrl)
            _activeTransport.value = TransportType.CELLULAR_LIVEKIT
        }
    }

    private fun switchToP2P() {
        scope.launch {
            liveKitTransport.stop()
            nearbyTransport.start(currentSessionId, "", "")
            _activeTransport.value = TransportType.LOCAL_P2P
        }
    }

    fun sendAudio(frame: ByteArray) {
        scope.launch {
            if (_activeTransport.value == TransportType.CELLULAR_LIVEKIT) {
                liveKitTransport.sendAudioFrame(frame)
            } else {
                nearbyTransport.sendAudioFrame(frame)
            }
        }
    }

    fun sendData(packet: DataPacket) {
        scope.launch {
            if (_activeTransport.value == TransportType.CELLULAR_LIVEKIT) {
                liveKitTransport.sendDataPacket(packet)
            } else {
                nearbyTransport.sendDataPacket(packet)
            }
        }
    }

    override fun onAudioFrameReceived(senderId: String, frame: ByteArray) {
        if (_activeTransport.value == TransportType.CELLULAR_LIVEKIT && nearbyTransport.state.value == TransportState.CONNECTED) {
            nearbyTransport.sendAudioFrame(frame)
        }
    }

    override fun onDataPacketReceived(packet: DataPacket) {
        if (_activeTransport.value == TransportType.CELLULAR_LIVEKIT && nearbyTransport.state.value == TransportState.CONNECTED) {
            liveKitTransport.sendDataPacket(packet)
        }
    }
}
