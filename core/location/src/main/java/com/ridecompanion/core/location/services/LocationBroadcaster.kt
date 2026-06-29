package com.ridecompanion.core.location.services

import android.location.Location
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that broadcasts location updates from the ForegroundService
 * to any ViewModel or UI component that needs them.
 */
@Singleton
class LocationBroadcaster @Inject constructor() {

    private val _locationUpdates = MutableSharedFlow<Location>(replay = 1)
    val locationUpdates: SharedFlow<Location> = _locationUpdates

    suspend fun broadcast(location: Location) {
        _locationUpdates.emit(location)
    }
}
