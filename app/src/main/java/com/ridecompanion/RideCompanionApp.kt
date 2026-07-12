package com.ridecompanion

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class RideCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid setup: identify ourselves to the tile server and give the
        // on-disk tile cache room for whole rides, so areas you've seen (and
        // pre-cached route corridors) keep working with no internet.
        Configuration.getInstance().apply {
            load(this@RideCompanionApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            // Generous enough for several ride corridors, small enough that
            // the tile database's trim passes stay cheap — at 600 MB the
            // trim's SQLite churn was enough to make budget phones stutter.
            tileFileSystemCacheMaxBytes = 250L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 200L * 1024 * 1024
            // Keep serving cached tiles long after their normal expiry — stale
            // roads beat a blank screen in a dead zone.
            expirationExtendedDuration = 30L * 24 * 60 * 60 * 1000
        }
    }
}
