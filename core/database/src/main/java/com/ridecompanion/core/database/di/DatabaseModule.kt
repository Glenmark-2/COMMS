package com.ridecompanion.core.database.di

import android.content.Context
import androidx.room.Room
import com.ridecompanion.core.database.RideDatabase
import com.ridecompanion.core.database.dao.RideDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRideDatabase(
        @ApplicationContext context: Context
    ): RideDatabase {
        return Room.databaseBuilder(
            context,
            RideDatabase::class.java,
            "ride_companion.db"
        ).fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    @Singleton
    fun provideRideDao(database: RideDatabase): RideDao {
        return database.rideDao()
    }
}
