package com.ridecompanion.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ridecompanion.core.database.dao.RideDao
import com.ridecompanion.core.database.entity.GPSPointEntity
import com.ridecompanion.core.database.entity.RideSessionEntity
import com.ridecompanion.core.database.entity.RiderStateEntity
import com.ridecompanion.core.database.entity.RideSummaryEntity

@Database(
    entities = [
        RiderStateEntity::class,
        RideSessionEntity::class,
        GPSPointEntity::class,
        RideSummaryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
}
