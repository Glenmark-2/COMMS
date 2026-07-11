package com.ridecompanion.core.location.utils

object BatteryOptimizer {

    // Turn-by-turn navigation needs ~1 s fixes to call turns on time and keep
    // the camera/heading smooth — the same rate real navigators use.
    private const val NAVIGATING_INTERVAL = 1000L
    private const val NAVIGATING_FASTEST = 1000L

    // Riders want the map to feel live even without a route set, so the whole
    // riding range stays at 1-2 s. Phones are usually charging on the mount.
    private const val FAST_RIDING_INTERVAL = 1000L      // 1 second
    private const val FAST_RIDING_FASTEST = 1000L

    private const val SLOW_RIDING_INTERVAL = 2000L      // 2 seconds
    private const val SLOW_RIDING_FASTEST = 1000L

    // Kept short enough that other riders' view of a stopped rider stays fresh
    // and the first pedal stroke is picked up immediately.
    private const val STOPPED_INTERVAL = 5000L          // 5 seconds
    private const val STOPPED_FASTEST = 2000L

    fun getOptimalInterval(speedMps: Float, navigating: Boolean = false): Long {
        if (navigating) return NAVIGATING_INTERVAL
        return when {
            speedMps > 5.5f -> FAST_RIDING_INTERVAL
            speedMps > 1.0f -> SLOW_RIDING_INTERVAL
            else -> STOPPED_INTERVAL
        }
    }

    fun getFastestInterval(speedMps: Float, navigating: Boolean = false): Long {
        if (navigating) return NAVIGATING_FASTEST
        return when {
            speedMps > 5.5f -> FAST_RIDING_FASTEST
            speedMps > 1.0f -> SLOW_RIDING_FASTEST
            else -> STOPPED_FASTEST
        }
    }
}
