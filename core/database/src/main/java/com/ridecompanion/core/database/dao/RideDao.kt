package com.ridecompanion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ridecompanion.core.database.entity.GPSPointEntity
import com.ridecompanion.core.database.entity.RideSessionEntity
import com.ridecompanion.core.database.entity.RiderStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {

    // Rider States Caches
    @Query("SELECT * FROM rider_states")
    fun getAllRiderStates(): Flow<List<RiderStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiderState(state: RiderStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiderStates(states: List<RiderStateEntity>)

    @Query("DELETE FROM rider_states WHERE userId = :userId")
    suspend fun deleteRiderState(userId: String)

    @Query("DELETE FROM rider_states")
    suspend fun clearRiderStates()

    // Active Ride Session
    @Query("SELECT * FROM ride_sessions WHERE isActive = 1 LIMIT 1")
    fun getActiveSession(): Flow<RideSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: RideSessionEntity)

    @Query("UPDATE ride_sessions SET isActive = 0 WHERE id = :sessionId")
    suspend fun deactivateSession(sessionId: String)

    @Query("DELETE FROM ride_sessions")
    suspend fun clearSessions()

    // GPS Recording (offline trace)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGPSPoint(point: GPSPointEntity)

    @Query("SELECT * FROM gps_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getGPSPointsForSession(sessionId: String): Flow<List<GPSPointEntity>>

    @Query("SELECT * FROM gps_points WHERE isSynced = 0")
    suspend fun getUnsyncedGPSPoints(): List<GPSPointEntity>

    @Query("UPDATE gps_points SET isSynced = 1 WHERE id IN (:pointIds)")
    suspend fun markPointsAsSynced(pointIds: List<Long>)
}
