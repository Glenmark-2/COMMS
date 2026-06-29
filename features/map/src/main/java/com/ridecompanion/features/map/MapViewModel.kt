package com.ridecompanion.features.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.database.dao.RideDao
import com.ridecompanion.core.database.entity.GPSPointEntity
import com.ridecompanion.core.navigation.engine.NavigationEngine
import com.ridecompanion.core.navigation.engine.RoutePoint
import com.ridecompanion.core.navigation.engine.SnapResult
import com.ridecompanion.core.common.model.RiderState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val currentRiderState: RiderState? = null,
    val otherRiders: List<RiderState> = emptyList(),
    val routePoints: List<RoutePoint> = emptyList(),
    val snapResult: SnapResult? = null,
    val isOffRoute: Boolean = false
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val rideDao: RideDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val offRouteThresholdMeters = 30.0

    init {
        observeRiderStates()
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
                        networkType = com.ridecompanion.core.common.model.NetworkType.valueOf(entity.networkType),
                        networkSignalStrength = entity.networkSignalStrength,
                        locationAccuracy = entity.locationAccuracy,
                        lastUpdated = entity.lastUpdated,
                        movementState = com.ridecompanion.core.common.model.MovementState.valueOf(entity.movementState)
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

    fun onLocationUpdate(latitude: Double, longitude: Double, heading: Float, speed: Float) {
        val route = _uiState.value.routePoints
        if (route.isEmpty()) return

        val snap = NavigationEngine.snapToRoute(latitude, longitude, route)
        val isOff = (snap?.distanceToRouteMeters ?: 0.0) > offRouteThresholdMeters

        _uiState.update { 
            it.copy(
                snapResult = snap,
                isOffRoute = isOff
            )
        }
    }
}
