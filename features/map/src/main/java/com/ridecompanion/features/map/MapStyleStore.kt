package com.ridecompanion.features.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers whether the rider prefers the bright (sunlight) or dark map, so
 * the choice survives app restarts.
 */
@Singleton
class MapStyleStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("map_style", Context.MODE_PRIVATE)

    var lightMap: Boolean
        get() = prefs.getBoolean("light", false)
        set(value) {
            prefs.edit().putBoolean("light", value).apply()
        }
}
