package com.ridecompanion.core.network.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Photon geocoder (https://photon.komoot.io) — free, keyless, OpenStreetMap based.
 * Used for destination search / autocomplete.
 */
interface PlacesApi {

    @GET("api/")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 10,
        @Query("lat") biasLat: Double? = null,
        @Query("lon") biasLon: Double? = null,
        @Query("lang") lang: String = "en"
    ): PhotonResponse

    /** Nearest named place/street for a coordinate — names dropped pins. */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("limit") limit: Int = 1,
        @Query("lang") lang: String = "en"
    ): PhotonResponse
}

data class PhotonResponse(
    val features: List<PhotonFeature> = emptyList()
)

data class PhotonFeature(
    val geometry: PhotonGeometry,
    val properties: PhotonProperties
)

data class PhotonGeometry(
    // GeoJSON order: [longitude, latitude]
    val coordinates: List<Double> = emptyList()
)

data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postcode: String? = null,
    @SerializedName("osm_key") val osmKey: String? = null,
    @SerializedName("osm_value") val osmValue: String? = null
)
