package com.ridecompanion.features.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.database.dao.RideDao
import com.ridecompanion.core.navigation.engine.NavigationEngine
import com.ridecompanion.core.navigation.engine.RoutePoint
import com.ridecompanion.core.navigation.engine.SnapResult
import com.ridecompanion.core.common.model.RiderState
import com.ridecompanion.core.common.model.NetworkType
import com.ridecompanion.core.common.model.MovementState
import com.ridecompanion.core.location.services.LocationBroadcaster
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val currentLocation: Location? = null,
    val currentRiderState: RiderState? = null,
    val otherRiders: List<RiderState> = emptyList(),
    val routePoints: List<RoutePoint> = emptyList(),
    val snapResult: SnapResult? = null,
    val isOffRoute: Boolean = false,
    val isFollowingUser: Boolean = true
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val rideDao: RideDao,
    private val locationBroadcaster: LocationBroadcaster
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val offRouteThresholdMeters = 30.0

    init {
        observeRiderStates()
        observeLocationUpdates()
    }

    private fun observeLocationUpdates() {
        viewModelScope.launch {
            locationBroadcaster.locationUpdates.collect { location ->
                _uiState.update { state ->
                    state.copy(currentLocation = location)
                }

                // Update snap-to-route if a route is loaded
                val route = _uiState.value.routePoints
                if (route.isNotEmpty()) {
                    val snap = NavigationEngine.snapToRoute(location.latitude, location.longitude, route)
                    val isOff = (snap?.distanceToRouteMeters ?: 0.0) > offRouteThresholdMeters
                    _uiState.update { it.copy(snapResult = snap, isOffRoute = isOff) }
                }
            }
        }
    }

    private fun observeRiderStates() {
        viewModelScope.launch {
            rideDao.getAllRiderStates().collect { entities ->
                val ridersList = entities.map { entity ->
                    RiderState(
                        userId = entity.userId,
                        latitude = entity.latitude,
                        longitude = entity.longitude,
                        heading = entity.heading,
                        speed = entity.speed,
                        batteryPercentage = entity.batteryPercentage,
                        networkType = NetworkType.valueOf(entity.networkType),
                        networkSignalStrength = entity.networkSignalStrength,
                        locationAccuracy = entity.locationAccuracy,
                        lastUpdated = entity.lastUpdated,
                        movementState = MovementState.valueOf(entity.movementState)
                    )
                }
                _uiState.update { it.copy(otherRiders = ridersList) }
            }
        }
    }

    fun loadGPXRoute(gpxXmlContent: String) {
        viewModelScope.launch {
            val parsedRoute = NavigationEngine.parseGPX(gpxXmlContent)
            _uiState.update { it.copy(routePoints = parsedRoute) }
        }
    }

    fun setFollowingUser(following: Boolean) {
        _uiState.update { it.copy(isFollowingUser = following) }
    }
}
