package com.ridecompanion.core.network.repository

import com.ridecompanion.core.common.model.GeoPoint
import com.ridecompanion.core.common.model.RoutePath
import com.ridecompanion.core.common.model.RouteProfile
import com.ridecompanion.core.common.model.RouteTurn
import com.ridecompanion.core.common.model.TurnKind
import com.ridecompanion.core.network.api.RoutingApi
import com.ridecompanion.core.network.api.ValhallaApi
import com.ridecompanion.core.network.api.ValhallaManeuver
import com.ridecompanion.core.network.api.decodePolyline6
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutingRepository @Inject constructor(
    private val valhallaApi: ValhallaApi,
    private val brouterApi: RoutingApi
) {

    /**
     * Compute a route from a start coordinate to a destination.
     *
     * Tries, in order:
     *  1. Valhalla bicycle — real turn-by-turn with street names.
     *  2. BRouter trekking — second bike engine, in case Valhalla is down.
     *  3. Valhalla pedestrian — some places simply have no cycleable way in
     *     OSM; a walking route still gets the rider there (bikes can be walked).
     *  4. Valhalla auto — last resort so "no route" almost never happens.
     *
     * Returns null only when every engine fails (offline, or truly unreachable).
     */
    suspend fun route(
        startLatitude: Double,
        startLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        destinationName: String
    ): RoutePath? {
        valhalla("bicycle", startLatitude, startLongitude, destinationLatitude, destinationLongitude, destinationName, RouteProfile.BIKE)
            ?.let { return it }
        brouter(startLatitude, startLongitude, destinationLatitude, destinationLongitude, destinationName)
            ?.let { return it }
        valhalla("pedestrian", startLatitude, startLongitude, destinationLatitude, destinationLongitude, destinationName, RouteProfile.FOOT)
            ?.let { return it }
        return valhalla("auto", startLatitude, startLongitude, destinationLatitude, destinationLongitude, destinationName, RouteProfile.CAR)
    }

    // ---------------- Valhalla ----------------

    private suspend fun valhalla(
        costing: String,
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        destinationName: String,
        profile: RouteProfile
    ): RoutePath? {
        return try {
            val request = JSONObject().apply {
                put("locations", JSONArray().apply {
                    // A generous snap radius so pins dropped off-road (parks,
                    // beaches, plazas) still find the nearest routable way.
                    put(JSONObject().put("lat", startLat).put("lon", startLon).put("radius", 300))
                    put(JSONObject().put("lat", destLat).put("lon", destLon).put("radius", 300))
                })
                put("costing", costing)
                if (costing == "bicycle") {
                    put("costing_options", JSONObject().put("bicycle", JSONObject().apply {
                        put("bicycle_type", "Hybrid")
                        // Balanced defaults: prefer quieter roads but don't
                        // send riders on huge detours to avoid every hill.
                        put("use_roads", 0.4)
                        put("use_hills", 0.5)
                    }))
                }
                put("units", "kilometers")
                put("language", "en-US")
            }
            val trip = valhallaApi.route(request.toString()).trip ?: return null
            if (trip.legs.isEmpty()) return null

            val points = ArrayList<GeoPoint>()
            val turns = ArrayList<RouteTurn>()
            for (leg in trip.legs) {
                val legOffset = points.size
                val shape = leg.shape ?: continue
                decodePolyline6(shape).forEach { (lat, lon) ->
                    points.add(GeoPoint(lat, lon))
                }
                for (maneuver in leg.maneuvers) {
                    val kind = maneuverKind(maneuver) ?: continue
                    val index = (legOffset + maneuver.beginShapeIndex)
                        .coerceIn(0, points.size - 1)
                    val at = points[index]
                    turns.add(
                        RouteTurn(
                            pointIndex = index,
                            latitude = at.latitude,
                            longitude = at.longitude,
                            kind = kind,
                            streetName = maneuver.streetNames?.firstOrNull()?.takeIf { it.isNotBlank() },
                            instruction = maneuver.instruction?.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            if (points.size < 2) return null

            RoutePath(
                points = points,
                distanceMeters = (trip.summary?.length ?: 0.0) * 1000.0,
                durationSeconds = trip.summary?.time ?: 0.0,
                ascentMeters = 0.0,
                destinationName = destinationName,
                destinationLatitude = destLat,
                destinationLongitude = destLon,
                turns = turns,
                profile = profile
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Map a Valhalla maneuver to a turn we guide on. Returns null for
     * maneuvers that aren't spoken (start/destination — arrival has its own
     * announcement) so they don't clutter the turn list.
     */
    private fun maneuverKind(m: ValhallaManeuver): TurnKind? = when (m.type) {
        // kStart*, kDestination*, transit and ferry legs — not spoken turns.
        0, 1, 2, 3, 4, 5, 6, 28, 29 -> null
        in 30..36 -> null
        9, 18, 20, 23 -> TurnKind.SLIGHT_RIGHT
        10 -> TurnKind.RIGHT
        11 -> TurnKind.SHARP_RIGHT
        12, 13 -> TurnKind.UTURN
        14 -> TurnKind.SHARP_LEFT
        15 -> TurnKind.LEFT
        16, 19, 21, 24 -> TurnKind.SLIGHT_LEFT
        26 -> TurnKind.ROUNDABOUT
        // kBecomes/kContinue/kStay*/kMerge*/kRoundaboutExit — "continue onto X".
        else -> TurnKind.STRAIGHT
    }

    // ---------------- BRouter (fallback bike engine) ----------------

    private suspend fun brouter(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        destinationName: String
    ): RoutePath? {
        return try {
            val lonlats = "$startLon,$startLat|$destLon,$destLat"
            val response = brouterApi.route(lonlats = lonlats)
            val feature = response.features.firstOrNull() ?: return null

            val points = feature.geometry.coordinates.mapNotNull { c ->
                if (c.size >= 2) {
                    GeoPoint(
                        latitude = c[1],
                        longitude = c[0],
                        altitude = if (c.size >= 3) c[2] else 0.0
                    )
                } else null
            }
            if (points.size < 2) return null

            RoutePath(
                points = points,
                distanceMeters = feature.properties?.trackLength?.toDoubleOrNull() ?: 0.0,
                durationSeconds = feature.properties?.totalTime?.toDoubleOrNull() ?: 0.0,
                ascentMeters = feature.properties?.ascend?.toDoubleOrNull() ?: 0.0,
                destinationName = destinationName,
                destinationLatitude = destLat,
                destinationLongitude = destLon,
                // BRouter has no maneuver data — turns are derived from the
                // geometry by the navigation engine.
                turns = emptyList(),
                profile = RouteProfile.BIKE
            )
        } catch (e: Exception) {
            null
        }
    }
}
