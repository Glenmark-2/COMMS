package com.ridecompanion.features.map

import android.content.Context
import com.ridecompanion.core.common.model.PlaceResult
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the last few destinations so riders can re-pick a frequent spot
 * with one tap instead of retyping it.
 */
@Singleton
class RecentDestinationsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("recent_destinations", Context.MODE_PRIVATE)

    companion object {
        private const val KEY = "recents"
        private const val MAX_ENTRIES = 6
    }

    fun load(): List<PlaceResult> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                PlaceResult(
                    name = o.optString("name").ifBlank { return@mapNotNull null },
                    description = o.optString("desc"),
                    latitude = o.optDouble("lat"),
                    longitude = o.optDouble("lon"),
                    category = o.optString("cat").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(place: PlaceResult) {
        // Most recent first, no duplicates (same name + rough coordinates).
        val updated = (listOf(place) + load().filterNot {
            it.name == place.name &&
                Math.abs(it.latitude - place.latitude) < 1e-4 &&
                Math.abs(it.longitude - place.longitude) < 1e-4
        }).take(MAX_ENTRIES)

        val array = JSONArray()
        updated.forEach { p ->
            array.put(JSONObject().apply {
                put("name", p.name)
                put("desc", p.description)
                put("lat", p.latitude)
                put("lon", p.longitude)
                put("cat", p.category ?: "")
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
