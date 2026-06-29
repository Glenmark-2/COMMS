package com.ridecompanion

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

@HiltAndroidApp
class RideCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
    }
}
