package com.ridecompanion.core.network.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * BRouter (https://brouter.de) — free, keyless bicycle routing engine built on
 * OpenStreetMap. The "trekking" profile is a sensible all-round cycling profile.
 */
interface RoutingApi {

    @GET("brouter")
    suspend fun route(
        // Format: "lon1,lat1|lon2,lat2"
        @Query("lonlats") lonlats: String,
        @Query("profile") profile: String = "trekking",
        @Query("alternativeidx") alternativeIndex: Int = 0,
        @Query("format") format: String = "geojson"
    ): BRouterResponse
}

data class BRouterResponse(
    val features: List<BRouterFeature> = emptyList()
)

data class BRouterFeature(
    val geometry: BRouterGeometry,
    val properties: BRouterProperties?
)

data class BRouterGeometry(
    // List of [longitude, latitude, altitude]
    val coordinates: List<List<Double>> = emptyList()
)

data class BRouterProperties(
    @SerializedName("track-length") val trackLength: String? = null,
    @SerializedName("total-time") val totalTime: String? = null,
    @SerializedName("filtered ascend") val ascend: String? = null
)
