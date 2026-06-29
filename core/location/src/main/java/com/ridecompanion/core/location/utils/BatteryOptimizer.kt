package com.ridecompanion.core.location.utils

object BatteryOptimizer {

    private const val FAST_RIDING_INTERVAL = 5000L      // 5 seconds
    private const val FAST_RIDING_FASTEST = 2000L       // 2 seconds

    private const val SLOW_RIDING_INTERVAL = 10000L     // 10 seconds
    private const val SLOW_RIDING_FASTEST = 5000L       // 5 seconds

    private const val STOPPED_INTERVAL = 45000L         // 45 seconds
    private const val STOPPED_FASTEST = 30000L          // 30 seconds

    fun getOptimalInterval(speedMps: Float): Long {
        return when {
            speedMps > 5.5f -> FAST_RIDING_INTERVAL
            speedMps > 1.0f -> SLOW_RIDING_INTERVAL
            else -> STOPPED_INTERVAL
        }
    }

    fun getFastestInterval(speedMps: Float): Long {
        return when {
            speedMps > 5.5f -> FAST_RIDING_FASTEST
            speedMps > 1.0f -> SLOW_RIDING_FASTEST
            else -> STOPPED_FASTEST
        }
    }
}
