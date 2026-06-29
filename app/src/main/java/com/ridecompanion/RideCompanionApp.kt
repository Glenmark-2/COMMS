package com.ridecompanion

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RideCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
