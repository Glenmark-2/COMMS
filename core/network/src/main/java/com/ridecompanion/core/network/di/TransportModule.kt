package com.ridecompanion.core.network.di

import android.content.Context
import com.ridecompanion.core.network.transport.AdaptiveTransportManager
import com.ridecompanion.core.network.transport.LiveKitTransport
import com.ridecompanion.core.network.transport.NearbyConnectionsTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideLiveKitTransport(
        @ApplicationContext context: Context
    ): LiveKitTransport {
        return LiveKitTransport(context)
    }

    @Provides
    @Singleton
    fun provideNearbyConnectionsTransport(
        @ApplicationContext context: Context
    ): NearbyConnectionsTransport {
        return NearbyConnectionsTransport(context)
    }

    @Provides
    @Singleton
    fun provideAdaptiveTransportManager(
        @ApplicationContext context: Context,
        liveKitTransport: LiveKitTransport,
        nearbyConnectionsTransport: NearbyConnectionsTransport
    ): AdaptiveTransportManager {
        return AdaptiveTransportManager(
            context,
            liveKitTransport,
            nearbyConnectionsTransport
        )
    }
}
