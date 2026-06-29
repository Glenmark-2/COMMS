package com.ridecompanion.core.voice.di

import android.content.Context
import com.ridecompanion.core.voice.manager.AudioVolumeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    @Provides
    @Singleton
    fun provideAudioVolumeManager(
        @ApplicationContext context: Context
    ): AudioVolumeManager {
        return AudioVolumeManager(context)
    }
}
