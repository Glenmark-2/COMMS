package com.ridecompanion.core.network.repository

import com.ridecompanion.core.common.model.PlaceResult
import com.ridecompanion.core.network.api.PhotonFeature
import com.ridecompanion.core.network.api.PlacesApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlacesRepository @Inject constructor(
    private val placesApi: PlacesApi
) {

    // Generic words people append that make geocoders match POIs literally named
    // "<X> City" instead of the settlement <X> (e.g. "Tagaytay City").
    private val genericSuffixes = setOf("city", "town", "municipality", "province", "village")

    // OSM place values that represent settlements, in priority order.
    private val settlementRank = mapOf(
        "city" to 0, "town" to 1, "municipality" to 1,
        "village" to 2, "suburb" to 2, "borough" to 2, "hamlet" to 3
    )

    /**
     * Search for destinations matching [query], biased toward the rider's current
     * location. Settlements (cities/towns) are surfaced above unrelated POIs, and a
     * trailing generic word like "City" is also tried stripped so famous places resolve.
     */
    suspend fun search(
        query: String,
        nearLatitude: Double? = null,
        nearLongitude: Double? = null
    ): List<PlaceResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val primary = runSearch(q, nearLatitude, nearLongitude)

        // If the query ends with a generic word, also search the bare name and merge.
        val stripped = strippedQuery(q)
        val secondary = if (stripped != null) runSearch(stripped, nearLatitude, nearLongitude) else emptyList()

        val merged = primary + secondary
        val seen = HashSet<String>()
        return merged
            .filter { seen.add("${round5(it.feature)};${it.feature.properties.name}") }
            .sortedBy { rankOf(it.feature) }          // settlements first (stable sort keeps relevance)
            .mapNotNull { it.result }
            .take(8)
    }

    /**
     * Name for a coordinate (dropped pin). Returns null when offline or the
     * area has no named features nearby.
     */
    suspend fun reverse(latitude: Double, longitude: Double): PlaceResult? {
        return try {
            placesApi.reverse(latitude, longitude)
                .features
                .firstNotNullOfOrNull { it.toPlaceResult() }
                // Keep the exact pinned coordinate — the nearby feature only names it.
                ?.copy(latitude = latitude, longitude = longitude)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun runSearch(
        query: String,
        lat: Double?,
        lon: Double?
    ): List<Ranked> {
        return try {
            placesApi.search(query, biasLat = lat, biasLon = lon)
                .features
                .map { Ranked(it, it.toPlaceResult()) }
                .filter { it.result != null }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private class Ranked(val feature: PhotonFeature, val result: PlaceResult?)

    private fun strippedQuery(q: String): String? {
        val tokens = q.split(Regex("\\s+"))
        if (tokens.size < 2) return null
        return if (tokens.last().lowercase() in genericSuffixes) {
            tokens.dropLast(1).joinToString(" ")
        } else null
    }

    private fun rankOf(feature: PhotonFeature): Int {
        val p = feature.properties
        if (p.osmKey == "place") {
            return settlementRank[p.osmValue] ?: 4
        }
        return 5
    }

    private fun round5(feature: PhotonFeature): String {
        val c = feature.geometry.coordinates
        if (c.size < 2) return "?"
        return "${Math.round(c[1] * 1e4)},${Math.round(c[0] * 1e4)}"
    }

    private fun PhotonFeature.toPlaceResult(): PlaceResult? {
        val coords = geometry.coordinates
        if (coords.size < 2) return null
        val lon = coords[0]
        val lat = coords[1]

        val title = properties.name
            ?: listOfNotNull(properties.street, properties.housenumber)
                .joinToString(" ")
                .ifBlank { properties.city }
            ?: return null

        val detail = listOfNotNull(
            properties.street?.takeIf { it != title },
            properties.city,
            properties.state,
            properties.country
        ).distinct().joinToString(", ")

        return PlaceResult(
            name = title,
            description = detail,
            latitude = lat,
            longitude = lon,
            category = listOfNotNull(properties.osmKey, properties.osmValue)
                .joinToString("/").ifBlank { null }
        )
    }
}
