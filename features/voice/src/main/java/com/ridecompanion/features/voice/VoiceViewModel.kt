package com.ridecompanion.features.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.network.transport.AdaptiveTransportManager
import com.ridecompanion.core.network.transport.TransportType
import com.ridecompanion.core.voice.manager.AudioVolumeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val volumeManager: AudioVolumeManager
) : ViewModel() {

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    val activeTransport: StateFlow<TransportType?> = transportManager.activeTransport
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val volumeScale: StateFlow<Float> = volumeManager.intercomVolumeScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    private val _speakingRiders = MutableStateFlow<List<String>>(emptyList())
    val speakingRiders: StateFlow<List<String>> = _speakingRiders

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun adjustVolume(newVolume: Float) {
        if (newVolume in 0.0f..1.0f) {
            if (newVolume == 0.0f) {
                volumeManager.startDucking()
            } else {
                volumeManager.stopDucking()
            }
        }
    }
}
