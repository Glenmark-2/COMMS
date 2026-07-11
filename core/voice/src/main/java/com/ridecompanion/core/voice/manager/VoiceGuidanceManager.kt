package com.ridecompanion.core.voice.manager

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks turn-by-turn navigation prompts using Android TTS, automatically
 * ducking the intercom volume while talking and restoring it afterwards.
 */
@Singleton
class VoiceGuidanceManager @Inject constructor(
    @ApplicationContext context: Context,
    private val volumeManager: AudioVolumeManager
) {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            // Language must be set after the engine reports ready — setting it
            // earlier is silently ignored by many TTS engines.
            onTtsReady()
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                volumeManager.startDucking()
            }
            override fun onDone(utteranceId: String?) {
                volumeManager.stopDucking()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                volumeManager.stopDucking()
            }
        })
    }

    private fun onTtsReady() {
        val result = tts.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        // Navigation-guidance usage: Android routes these prompts like a nav
        // app — over Bluetooth helmet headsets, mixed correctly with the
        // intercom, and unaffected by the media volume.
        runCatching {
            tts.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        ready = true
    }

    /**
     * Speak a prompt. Navigation guidance interrupts whatever is playing
     * (a turn can't wait); pass [interrupt] = false for lower-priority
     * announcements (rider joined/left) so they queue behind guidance.
     */
    fun speak(text: String, interrupt: Boolean = true) {
        if (!ready) return
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "nav-$text")
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
