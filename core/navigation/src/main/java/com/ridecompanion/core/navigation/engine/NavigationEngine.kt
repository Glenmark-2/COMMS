package com.ridecompanion.core.navigation.engine

import kotlin.math.*

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val index: Int = 0
)

data class SnapResult(
    val closestPoint: RoutePoint,
    val distanceToRouteMeters: Double,
    val bearingToRejoinDegrees: Float,
    val routeSegmentIndex: Int
)

object NavigationEngine {

    private const val EARTH_RADIUS_METERS = 6371000.0

    fun parseGPX(gpxContent: String): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        val regex = "<trkpt\\s+lat=\"([^\"]+)\"\\s+lon=\"([^\"]+)\"".toRegex()
        var index = 0
        regex.findAll(gpxContent).forEach { matchResult ->
            val lat = matchResult.groupValues[1].toDoubleOrNull()
            val lon = matchResult.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) {
                points.add(RoutePoint(lat, lon, index = index++))
            }
        }
        return points
    }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLonRad = Math.toRadians(lon2 - lon1)

        val y = sin(dLonRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)
        val brngRad = atan2(y, x)
        
        return ((Math.toDegrees(brngRad) + 360) % 360).toFloat()
    }

    fun snapToRoute(
        riderLat: Double,
        riderLon: Double,
        routePoints: List<RoutePoint>
    ): SnapResult? {
        if (routePoints.size < 2) return null

        var minDistance = Double.MAX_VALUE
        var closestRoutePoint = routePoints[0]
        var closestSegmentIndex = 0

        for (i in 0 until routePoints.size - 1) {
            val pA = routePoints[i]
            val pB = routePoints[i + 1]

            val midLat = (pA.latitude + pB.latitude) / 2.0
            val degToRad = Math.PI / 180.0
            val cosMidLat = cos(midLat * degToRad)

            val dx = (pB.longitude - pA.longitude) * cosMidLat * 111320.0
            val dy = (pB.latitude - pA.latitude) * 111000.0

            val px = (riderLon - pA.longitude) * cosMidLat * 111320.0
            val py = (riderLat - pA.latitude) * 111000.0

            val segLengthSquared = dx * dx + dy * dy
            val t = if (segLengthSquared == 0.0) 0.0 else {
                val rawT = (px * dx + py * dy) / segLengthSquared
                rawT.coerceIn(0.0, 1.0)
            }

            val snapLat = pA.latitude + t * (pB.latitude - pA.latitude)
            val snapLon = pA.longitude + t * (pB.longitude - pA.longitude)

            val dist = calculateDistanceMeters(riderLat, riderLon, snapLat, snapLon)
            if (dist < minDistance) {
                minDistance = dist
                closestRoutePoint = RoutePoint(snapLat, snapLon, index = i)
                closestSegmentIndex = i
            }
        }

        val bearingToRejoin = calculateBearingDegrees(
            riderLat,
            riderLon,
            closestRoutePoint.latitude,
            closestRoutePoint.longitude
        )

        return SnapResult(
            closestPoint = closestRoutePoint,
            distanceToRouteMeters = minDistance,
            bearingToRejoinDegrees = bearingToRejoin,
            routeSegmentIndex = closestSegmentIndex
        )
    }
}
