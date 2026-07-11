package com.ridecompanion.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rider_states")
data class RiderStateEntity(
    @PrimaryKey val userId: String,
    val latitude: Double,
    val longitude: Double,
    val heading: Float,
    val speed: Float,
    val batteryPercentage: Int,
    val networkType: String,
    val networkSignalStrength: Int,
    val locationAccuracy: Float,
    val lastUpdated: Long,
    val movementState: String
)

@Entity(tableName = "ride_sessions")
data class RideSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val leaderId: String,
    val destinationName: String?,
    val destinationLatitude: Double?,
    val destinationLongitude: Double?,
    val gpxPathJson: String?,
    val isActive: Boolean
)

@Entity(tableName = "ride_summaries")
data class RideSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Double,
    val durationMillis: Long,
    val avgSpeedMps: Float,
    val maxSpeedMps: Float
)

@Entity(tableName = "gps_points")
data class GPSPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val heading: Float,
    val timestamp: Long,
    val isSynced: Boolean
)
