package com.ridecompanion.core.network.transport

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

private const val TAG = "NearbyTransport"

// Wire format: first byte tags the frame kind, rest is the body.
private const val FRAME_AUDIO: Byte = 0x01
private const val FRAME_DATA: Byte = 0x02

// Voice: 16 kHz mono PCM16, 40 ms frames (1280 bytes) — small enough for
// low latency, large enough to keep Nearby's per-payload overhead sane.
private const val SAMPLE_RATE = 16000
private const val FRAME_BYTES = 1280

/**
 * Offline peer-to-peer transport over Google Nearby Connections (Bluetooth +
 * Wi-Fi Direct). Carries both the voice intercom (raw PCM frames) and data
 * packets (location/route/SOS as JSON) so the group keeps working with no
 * internet at all.
 */
class NearbyConnectionsTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : CommunicationTransport {

    override val transportType = TransportType.LOCAL_P2P

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state

    // Riders currently talking on the mesh, detected from the audio energy of
    // their incoming frames — LiveKit reports speakers itself, Nearby doesn't.
    private val _activeSpeakers = MutableStateFlow<List<String>>(emptyList())
    val activeSpeakers: StateFlow<List<String>> = _activeSpeakers

    private val _riderJoined = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val riderJoined: SharedFlow<String> = _riderJoined

    private val _riderLeft = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val riderLeft: SharedFlow<String> = _riderLeft

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpoints = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // endpointId → rider display name (from the discovery handshake).
    private val endpointNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    // endpointId → last time their audio was above the speech threshold.
    private val lastVoiceAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var speakerDecayJob: Job? = null

    private var payloadListener: PayloadListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentSessionId: String = ""
    @Volatile private var localRiderName: String = "Rider"
    private val serviceId = "com.ridecompanion.intercom"

    // ---- Voice capture / playback ----
    @Volatile private var micEnabled = true
    @Volatile private var playbackVolume = 1.0f
    private var captureJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // The peer advertises its rider name — remember it for speaker
            // attribution and join/leave announcements.
            endpointNames[endpointId] = info.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectedEndpoints.add(endpointId)
                    _state.value = TransportState.CONNECTED
                    _riderJoined.tryEmit(riderNameOf(endpointId))
                    startVoiceCaptureIfNeeded()
                }
                else -> {}
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoints.remove(endpointId)) {
                _riderLeft.tryEmit(riderNameOf(endpointId))
            }
            lastVoiceAtMillis.remove(endpointId)
            if (connectedEndpoints.isEmpty()) {
                _state.value = if (isStarted) TransportState.CONNECTING else TransportState.DISCONNECTED
                stopVoiceCapture()
            }
        }
    }

    private fun riderNameOf(endpointId: String): String =
        endpointNames[endpointId] ?: "A rider"

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(
                localRiderName,
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty()) return
            when (bytes[0]) {
                FRAME_AUDIO -> {
                    val pcm = bytes.copyOfRange(1, bytes.size)
                    detectSpeech(endpointId, pcm)
                    playAudioFrame(pcm)
                    payloadListener?.onAudioFrameReceived(endpointId, pcm)
                }
                FRAME_DATA -> {
                    val packet = decodeDataFrame(bytes) ?: return
                    payloadListener?.onDataPacketReceived(packet)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    @Volatile private var isStarted = false

    override fun start(sessionId: String, connectionToken: String, signalingUrl: String) {
        if (isStarted) return
        isStarted = true
        currentSessionId = sessionId
        _state.value = TransportState.CONNECTING

        startAdvertising()
        startDiscovery()
        startSpeakerDecay()
    }

    // ---- Speaking detection (simple energy VAD on incoming PCM) ----

    /** RMS above this on 16-bit PCM counts as speech (wind/idle noise sits lower). */
    private val speechRmsThreshold = 900.0

    private fun detectSpeech(endpointId: String, pcm: ByteArray) {
        var sumSquares = 0.0
        var samples = 0
        // Every 4th 16-bit sample is plenty for an energy estimate.
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sumSquares += sample * sample.toDouble()
            samples++
            i += 8
        }
        if (samples == 0) return
        val rms = kotlin.math.sqrt(sumSquares / samples)
        if (rms >= speechRmsThreshold) {
            lastVoiceAtMillis[endpointId] = System.currentTimeMillis()
        }
    }

    /** Keep the speakers list fresh: a rider stays "speaking" for a short tail. */
    private fun startSpeakerDecay() {
        speakerDecayJob?.cancel()
        speakerDecayJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val speaking = lastVoiceAtMillis
                    .filterValues { now - it < 900L }
                    .keys
                    .map { riderNameOf(it) }
                    .sorted()
                if (_activeSpeakers.value != speaking) {
                    _activeSpeakers.value = speaking
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /** The rider's display name, shown to peers during discovery. */
    fun setLocalRiderName(name: String) {
        if (name.isNotBlank()) localRiderName = name
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            localRiderName,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed: ${e.message}")
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
        ).addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed: ${e.message}")
        }
    }

    override fun stop() {
        isStarted = false
        _state.value = TransportState.DISCONNECTED
        stopVoiceCapture()
        releaseAudioTrack()
        speakerDecayJob?.cancel()
        speakerDecayJob = null
        _activeSpeakers.value = emptyList()
        lastVoiceAtMillis.clear()
        endpointNames.clear()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
    }

    // ================= Voice =================

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        if (enabled) startVoiceCaptureIfNeeded() else stopVoiceCapture()
    }

    fun setPlaybackVolume(scale: Float) {
        playbackVolume = scale.coerceIn(0f, 1f)
        audioTrack?.let { runCatching { it.setVolume(playbackVolume) } }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested at app start
    private fun startVoiceCaptureIfNeeded() {
        if (!micEnabled || connectedEndpoints.isEmpty() || captureJob?.isActive == true) return
        captureJob = scope.launch {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, FRAME_BYTES * 4)
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }
                recorder.startRecording()
                val buffer = ByteArray(FRAME_BYTES)
                while (isActive && micEnabled && connectedEndpoints.isNotEmpty()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val frame = ByteArray(read + 1)
                        frame[0] = FRAME_AUDIO
                        System.arraycopy(buffer, 0, frame, 1, read)
                        connectionsClient.sendPayload(
                            connectedEndpoints.toList(), Payload.fromBytes(frame)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice capture error: ${e.message}")
            } finally {
                runCatching {
                    recorder?.stop()
                    recorder?.release()
                }
            }
        }
    }

    private fun stopVoiceCapture() {
        captureJob?.cancel()
        captureJob = null
    }

    private fun playAudioFrame(pcm: ByteArray) {
        val track = audioTrack ?: createAudioTrack().also { audioTrack = it } ?: return
        runCatching {
            track.write(pcm, 0, pcm.size)
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.setVolume(playbackVolume) }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack create failed: ${e.message}")
            null
        }
    }

    private fun releaseAudioTrack() {
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    // ================= Data =================

    override fun sendAudioFrame(frame: ByteArray) {
        if (connectedEndpoints.isEmpty()) return
        val tagged = ByteArray(frame.size + 1)
        tagged[0] = FRAME_AUDIO
        System.arraycopy(frame, 0, tagged, 1, frame.size)
        connectionsClient.sendPayload(connectedEndpoints.toList(), Payload.fromBytes(tagged))
    }

    override fun sendDataPacket(packet: DataPacket) {
        if (connectedEndpoints.isEmpty()) return
        val envelope = JSONObject().apply {
            put("t", packet.packetType.name)
            put("s", if (packet.senderId == "self") localRiderName else packet.senderId)
            put("f", packet.forwarded)
            put("p", String(packet.payload, Charsets.UTF_8))
        }
        val body = envelope.toString().toByteArray(Charsets.UTF_8)
        val tagged = ByteArray(body.size + 1)
        tagged[0] = FRAME_DATA
        System.arraycopy(body, 0, tagged, 1, body.size)
        connectionsClient.sendPayload(connectedEndpoints.toList(), Payload.fromBytes(tagged))
    }

    private fun decodeDataFrame(bytes: ByteArray): DataPacket? {
        return try {
            val json = JSONObject(String(bytes, 1, bytes.size - 1, Charsets.UTF_8))
            DataPacket(
                senderId = json.optString("s", "rider"),
                sessionId = currentSessionId,
                packetType = runCatching { PacketType.valueOf(json.getString("t")) }
                    .getOrDefault(PacketType.LOCATION),
                payload = json.optString("p", "{}").toByteArray(Charsets.UTF_8),
                forwarded = json.optBoolean("f", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bad data frame: ${e.message}")
            null
        }
    }

    override fun registerPayloadListener(listener: PayloadListener) {
        this.payloadListener = listener
    }

    override fun unregisterPayloadListener() {
        this.payloadListener = null
    }
}
