package com.ridecompanion.core.network.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Valhalla routing engine on the free, keyless FOSSGIS server
 * (https://valhalla.openstreetmap.de). Unlike BRouter it returns real
 * turn-by-turn maneuvers with street names ("Turn left onto Main Street"),
 * and it snaps start/end points to the nearest routable way, so a pin
 * dropped in a field still produces a route.
 */
interface ValhallaApi {

    // Valhalla takes its whole request as a JSON document in the `json` query
    // parameter. The request body is built in RoutingRepository.
    @GET("route")
    suspend fun route(@Query("json") json: String): ValhallaResponse
}

data class ValhallaResponse(
    val trip: ValhallaTrip? = null
)

data class ValhallaTrip(
    val legs: List<ValhallaLeg> = emptyList(),
    val summary: ValhallaSummary? = null
)

data class ValhallaLeg(
    val maneuvers: List<ValhallaManeuver> = emptyList(),
    // Encoded polyline, 1e-6 precision.
    val shape: String? = null
)

data class ValhallaSummary(
    // Kilometers (we request "units":"kilometers").
    val length: Double = 0.0,
    // Seconds.
    val time: Double = 0.0
)

data class ValhallaManeuver(
    val type: Int = 0,
    val instruction: String? = null,
    @SerializedName("street_names") val streetNames: List<String>? = null,
    @SerializedName("begin_shape_index") val beginShapeIndex: Int = 0,
    val length: Double = 0.0,
    val time: Double = 0.0
)

/** Decode a Valhalla/Google encoded polyline with 1e-6 coordinate precision. */
fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
    val points = ArrayList<Pair<Double, Double>>()
    var index = 0
    var lat = 0L
    var lon = 0L
    while (index < encoded.length) {
        var result = 0L
        var shift = 0
        var byte: Int
        do {
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f).toLong() shl shift)
            shift += 5
        } while (byte >= 0x20)
        lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

        result = 0L
        shift = 0
        do {
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f).toLong() shl shift)
            shift += 5
        } while (byte >= 0x20)
        lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

        points.add(lat / 1e6 to lon / 1e6)
    }
    return points
}
