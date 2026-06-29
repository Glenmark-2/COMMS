package com.ridecompanion.core.network.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class NearbyConnectionsTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : CommunicationTransport {

    override val transportType = TransportType.LOCAL_P2P
    
    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = mutableSetOf<String>()
    
    private var payloadListener: PayloadListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var currentSessionId: String
    private val serviceId = "com.ridecompanion.intercom"

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectedEndpoints.add(endpointId)
                    _state.value = TransportState.CONNECTED
                }
                else -> {}
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            if (connectedEndpoints.isEmpty()) {
                _state.value = TransportState.DISCONNECTED
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(
                "Rider",
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val packet = DataPacket(
                    senderId = endpointId,
                    sessionId = currentSessionId,
                    packetType = PacketType.LOCATION,
                    payload = bytes
                )
                payloadListener?.onDataPacketReceived(packet)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    override fun start(sessionId: String, connectionToken: String, signalingUrl: String) {
        if (_state.value != TransportState.DISCONNECTED) return
        
        currentSessionId = sessionId
        _state.value = TransportState.CONNECTING
        
        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            "RiderNode",
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener {
            _state.value = TransportState.DISCONNECTED
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnFailureListener {
            _state.value = TransportState.DISCONNECTED
        }
    }

    override fun stop() {
        scope.launch {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
            _state.value = TransportState.DISCONNECTED
        }
    }

    override fun sendAudioFrame(frame: ByteArray) {
        if (connectedEndpoints.isEmpty()) return
        val payload = Payload.fromBytes(frame)
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
    }

    override fun sendDataPacket(packet: DataPacket) {
        if (connectedEndpoints.isEmpty()) return
        val payload = Payload.fromBytes(packet.payload)
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
    }

    override fun registerPayloadListener(listener: PayloadListener) {
        this.payloadListener = listener
    }

    override fun unregisterPayloadListener() {
        this.payloadListener = null
    }
}
