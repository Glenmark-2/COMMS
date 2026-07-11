package com.ridecompanion.core.voice.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val _intercomVolumeScale = MutableStateFlow(1.0f)
    val intercomVolumeScale: StateFlow<Float> = _intercomVolumeScale

    // The rider's chosen slider volume. Ducking and focus changes scale
    // relative to this and restore it afterwards, instead of jumping to 100%.
    @Volatile private var userVolume = 1.0f
    @Volatile private var duckFactor = 1.0f

    private var audioFocusRequest: AudioFocusRequest? = null
    private var focusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    private fun applyVolume() {
        _intercomVolumeScale.value = (userVolume * duckFactor).coerceIn(0f, 1f)
    }

    init {
        setupFocusListener()
    }

    private fun setupFocusListener() {
        focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            duckFactor = when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> 0.0f
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 0.2f
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.3f
                AudioManager.AUDIOFOCUS_GAIN -> 1.0f
                else -> duckFactor
            }
            applyVolume()
        }
    }

    fun requestIntercomFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener!!)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonIntercomFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        duckFactor = 1.0f
        applyVolume()
    }

    /** Set the intercom volume directly (0f..1f) — driven by the volume slider. */
    fun setIntercomVolume(scale: Float) {
        userVolume = scale.coerceIn(0f, 1f)
        applyVolume()
    }

    /** Duck the intercom while a navigation prompt is speaking. */
    fun startDucking() {
        duckFactor = 0.3f
        applyVolume()
    }

    /** Restore the rider's chosen volume after the prompt finishes. */
    fun stopDucking() {
        duckFactor = 1.0f
        applyVolume()
    }
}
