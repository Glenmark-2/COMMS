package com.ridecompanion.features.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.network.transport.AdaptiveTransportManager
import com.ridecompanion.core.network.transport.TransportType
import com.ridecompanion.core.voice.manager.AudioVolumeManager
import com.ridecompanion.core.voice.manager.VoiceGuidanceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceUiState(
    val activeTransport: TransportType?,
    val isMuted: Boolean,
    val volumeScale: Float,
    val speakingRiders: List<String>
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val transportManager: AdaptiveTransportManager,
    private val volumeManager: AudioVolumeManager,
    private val voiceGuidanceManager: VoiceGuidanceManager
) : ViewModel() {

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    val activeTransport: StateFlow<TransportType?> = transportManager.activeTransport
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val volumeScale: StateFlow<Float> = volumeManager.intercomVolumeScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val speakingRiders: StateFlow<List<String>> = transportManager.activeSpeakers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Apply the intercom volume (manual slider + automatic nav ducking) to the
        // actual remote audio playback whenever it changes.
        viewModelScope.launch {
            volumeManager.intercomVolumeScale.collect { scale ->
                transportManager.setRemoteVolume(scale)
            }
        }
        // Say who joins or leaves — riders keep their eyes on the road, so
        // presence changes are spoken, not just shown.
        viewModelScope.launch {
            transportManager.riderEvents.collect { event ->
                val verb = if (event.joined) "joined" else "left"
                // Don't interrupt an in-flight turn instruction for this.
                voiceGuidanceManager.speak("${event.riderName} $verb the ride.", interrupt = false)
            }
        }
        // Hold audio focus while the intercom is live so music apps duck and
        // the system routes audio correctly to headsets.
        viewModelScope.launch {
            var hasFocus = false
            transportManager.activeTransport.collect { transport ->
                if (transport != null && !hasFocus) {
                    hasFocus = volumeManager.requestIntercomFocus()
                } else if (transport == null && hasFocus) {
                    volumeManager.abandonIntercomFocus()
                    hasFocus = false
                }
            }
        }
    }

    fun toggleMute() {
        val muted = !_isMuted.value
        _isMuted.value = muted
        transportManager.setMicEnabled(!muted)
    }

    fun adjustVolume(newVolume: Float) {
        volumeManager.setIntercomVolume(newVolume)
    }
}
