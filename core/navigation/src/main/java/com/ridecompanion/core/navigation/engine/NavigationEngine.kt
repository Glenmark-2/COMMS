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

enum class TurnDirection {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT
}

data class TurnInstruction(
    val routeIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val direction: TurnDirection,
    /** Road the turn leads onto — spoken and shown when the map data names it. */
    val streetName: String? = null,
    /** Full engine-provided sentence, e.g. "Turn left onto Main Street." */
    val instruction: String? = null
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

    /**
     * Distance remaining along the route from a snapped position to the end.
     * Uses the snap result's segment index and snapped point so it follows the
     * road, not a straight line.
     */
    fun remainingDistanceMeters(routePoints: List<RoutePoint>, snap: SnapResult): Double {
        if (routePoints.size < 2) return 0.0
        val i = snap.routeSegmentIndex.coerceIn(0, routePoints.size - 2)
        // From the snapped point to the end of its segment...
        var total = calculateDistanceMeters(
            snap.closestPoint.latitude, snap.closestPoint.longitude,
            routePoints[i + 1].latitude, routePoints[i + 1].longitude
        )
        // ...plus every remaining full segment.
        for (j in i + 1 until routePoints.size - 1) {
            total += calculateDistanceMeters(
                routePoints[j].latitude, routePoints[j].longitude,
                routePoints[j + 1].latitude, routePoints[j + 1].longitude
            )
        }
        return total
    }

    /**
     * Derive turn-by-turn instructions from the route geometry by measuring the
     * heading change at each vertex. Works for any route source (BRouter, GPX).
     */
    fun computeTurns(routePoints: List<RoutePoint>): List<TurnInstruction> {
        if (routePoints.size < 3) return emptyList()
        val turns = ArrayList<TurnInstruction>()
        for (i in 1 until routePoints.size - 1) {
            val b1 = calculateBearingDegrees(
                routePoints[i - 1].latitude, routePoints[i - 1].longitude,
                routePoints[i].latitude, routePoints[i].longitude
            )
            val b2 = calculateBearingDegrees(
                routePoints[i].latitude, routePoints[i].longitude,
                routePoints[i + 1].latitude, routePoints[i + 1].longitude
            )
            var delta = b2 - b1
            while (delta > 180f) delta -= 360f
            while (delta < -180f) delta += 360f
            val magnitude = abs(delta)
            if (magnitude < 25f) continue // essentially straight

            // Positive delta = bearing increasing clockwise = turning right.
            val direction = if (delta > 0) {
                when {
                    magnitude > 150f -> TurnDirection.UTURN
                    magnitude > 110f -> TurnDirection.SHARP_RIGHT
                    magnitude < 45f -> TurnDirection.SLIGHT_RIGHT
                    else -> TurnDirection.RIGHT
                }
            } else {
                when {
                    magnitude > 150f -> TurnDirection.UTURN
                    magnitude > 110f -> TurnDirection.SHARP_LEFT
                    magnitude < 45f -> TurnDirection.SLIGHT_LEFT
                    else -> TurnDirection.LEFT
                }
            }
            turns.add(TurnInstruction(i, routePoints[i].latitude, routePoints[i].longitude, direction))
        }
        return turns
    }

    /** Distance along the route from the snapped position to a given vertex index. */
    fun distanceAlongRoute(routePoints: List<RoutePoint>, snap: SnapResult, toIndex: Int): Double {
        if (routePoints.size < 2 || toIndex <= snap.routeSegmentIndex) return 0.0
        val i = snap.routeSegmentIndex.coerceIn(0, routePoints.size - 2)
        var total = calculateDistanceMeters(
            snap.closestPoint.latitude, snap.closestPoint.longitude,
            routePoints[i + 1].latitude, routePoints[i + 1].longitude
        )
        val end = toIndex.coerceAtMost(routePoints.size - 1)
        for (j in i + 1 until end) {
            total += calculateDistanceMeters(
                routePoints[j].latitude, routePoints[j].longitude,
                routePoints[j + 1].latitude, routePoints[j + 1].longitude
            )
        }
        return total
    }

    // When a progress hint is available, only search this window around it.
    // Prevents snapping to a distant later part of the route (out-and-back
    // legs, loops, streets the route crosses twice) — the classic cause of
    // navigation "jumping ahead". ~10 segments back tolerates GPS noise,
    // ~80 ahead covers several hundred meters of fast riding between fixes.
    private const val SNAP_WINDOW_BACK = 10
    private const val SNAP_WINDOW_AHEAD = 80

    // If the windowed snap is farther than this, the rider likely left the
    // route on purpose (shortcut) — fall back to searching the whole route.
    private const val SNAP_WINDOW_TRUST_METERS = 60.0

    /**
     * Snap the rider to the route. Pass the previous fix's segment index as
     * [hintSegmentIndex] so progress along the route stays monotonic; -1 for
     * the first fix (searches the entire route).
     */
    fun snapToRoute(
        riderLat: Double,
        riderLon: Double,
        routePoints: List<RoutePoint>,
        hintSegmentIndex: Int = -1
    ): SnapResult? {
        if (routePoints.size < 2) return null

        if (hintSegmentIndex >= 0) {
            val from = (hintSegmentIndex - SNAP_WINDOW_BACK).coerceAtLeast(0)
            val to = (hintSegmentIndex + SNAP_WINDOW_AHEAD).coerceAtMost(routePoints.size - 1)
            val windowed = snapToRouteRange(riderLat, riderLon, routePoints, from, to)
            if (windowed != null &&
                (windowed.distanceToRouteMeters <= SNAP_WINDOW_TRUST_METERS ||
                    (from == 0 && to == routePoints.size - 1))
            ) {
                return windowed
            }
        }
        return snapToRouteRange(riderLat, riderLon, routePoints, 0, routePoints.size - 1)
    }

    private fun snapToRouteRange(
        riderLat: Double,
        riderLon: Double,
        routePoints: List<RoutePoint>,
        fromIndex: Int,
        toIndex: Int
    ): SnapResult? {
        if (toIndex - fromIndex < 1) return null

        var minDistance = Double.MAX_VALUE
        var closestRoutePoint = routePoints[fromIndex]
        var closestSegmentIndex = fromIndex

        for (i in fromIndex until toIndex) {
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
