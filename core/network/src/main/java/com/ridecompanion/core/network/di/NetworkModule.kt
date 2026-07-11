package com.ridecompanion.core.network.di

import com.ridecompanion.core.network.api.PlacesApi
import com.ridecompanion.core.network.api.RideApi
import com.ridecompanion.core.network.api.RoutingApi
import com.ridecompanion.core.network.api.ValhallaApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://comms-jltz.onrender.com/"

    // Free, keyless OpenStreetMap services so the app works with no API-key setup.
    private const val PHOTON_URL = "https://photon.komoot.io/"
    private const val BROUTER_URL = "https://brouter.de/"
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/"

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            // BASIC: method/URL/status only — BODY would log every rider
            // coordinate and slow down large route responses.
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideRideApi(retrofit: Retrofit): RideApi {
        return retrofit.create(RideApi::class.java)
    }

    @Provides
    @Singleton
    @Named("photon")
    fun providePhotonRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(PHOTON_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun providePlacesApi(@Named("photon") retrofit: Retrofit): PlacesApi {
        return retrofit.create(PlacesApi::class.java)
    }

    @Provides
    @Singleton
    @Named("brouter")
    fun provideBRouterRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BROUTER_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideRoutingApi(@Named("brouter") retrofit: Retrofit): RoutingApi {
        return retrofit.create(RoutingApi::class.java)
    }

    @Provides
    @Singleton
    @Named("valhalla")
    fun provideValhallaRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(VALHALLA_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideValhallaApi(@Named("valhalla") retrofit: Retrofit): ValhallaApi {
        return retrofit.create(ValhallaApi::class.java)
    }
}
