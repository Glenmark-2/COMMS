package com.ridecompanion.core.common.model

data class Rider(
    val id: String,
    val name: String,
    val isLeader: Boolean
)

enum class MovementState {
    STOPPED,
    RIDING,
    CRASH_SUSPECTED,
    SOS
}

enum class NetworkType {
    WIFI,
    CELLULAR,
    OFFLINE
}

data class RiderState(
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val heading: Float,
    val speed: Float,
    val batteryPercentage: Int,
    val networkType: NetworkType,
    val networkSignalStrength: Int, // 1 (poor) to 4 (excellent)
    val locationAccuracy: Float,
    val lastUpdated: Long,
    val movementState: MovementState
)

data class RideSession(
    val id: String,
    val name: String,
    val leaderId: String,
    val riders: List<Rider>,
    val destinationName: String?,
    val destinationLatitude: Double?,
    val destinationLongitude: Double?,
    val gpxPathJson: String?, // Serialized GPX track for synchronization
    val isActive: Boolean
)
