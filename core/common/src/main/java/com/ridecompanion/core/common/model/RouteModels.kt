package com.ridecompanion.core.common.model

/** A single geographic coordinate. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0
)

/** A place returned by the geocoder (destination search). */
data class PlaceResult(
    val name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    // OSM classification ("place/city", "amenity/restaurant", …) so the UI
    // can show a matching icon for each result.
    val category: String? = null
)

/** Which travel mode the route was computed for. */
enum class RouteProfile {
    BIKE,
    /** Fallback when no cycleable way reaches the destination. */
    FOOT,
    /** Last-resort fallback — follows roads; the rider should stay alert. */
    CAR
}

/** The shape of a single turn, independent of any routing engine. */
enum class TurnKind {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT
}

/**
 * A turn along a route, as reported by the routing engine — carries the road
 * name so guidance can say "turn left onto Main Street" instead of just
 * "turn left".
 */
data class RouteTurn(
    /** Index of the turn's vertex in [RoutePath.points]. */
    val pointIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val kind: TurnKind,
    /** Road the turn leads onto, when the map data names it. */
    val streetName: String? = null,
    /** Engine-provided display sentence, e.g. "Turn left onto Main Street." */
    val instruction: String? = null
)

/** A computed route from the current location to a destination. */
data class RoutePath(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val ascentMeters: Double,
    val destinationName: String,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    /** Engine-provided turns with road names; empty when only geometry is known. */
    val turns: List<RouteTurn> = emptyList(),
    val profile: RouteProfile = RouteProfile.BIKE
)
