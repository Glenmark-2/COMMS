package com.ridecompanion.features.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SavedSession(
    val sessionId: String,
    val token: String,
    val liveKitUrl: String,
    val websocketUrl: String
)

/**
 * Persists the active session so a ride survives the app being killed/restarted,
 * and remembers the rider's name between launches.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("ride_session", Context.MODE_PRIVATE)

    var riderName: String
        get() = prefs.getString("rider_name", "") ?: ""
        set(value) { prefs.edit().putString("rider_name", value).apply() }

    fun saveActiveSession(session: SavedSession, riderName: String) {
        prefs.edit()
            .putString("sid", session.sessionId)
            .putString("token", session.token)
            .putString("lkurl", session.liveKitUrl)
            .putString("wsurl", session.websocketUrl)
            .putString("rider_name", riderName)
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    fun clearActiveSession() {
        prefs.edit()
            .remove("sid").remove("token").remove("lkurl").remove("wsurl").remove("saved_at")
            .apply()
    }

    /** Returns a resumable session, or null if none or the token is likely expired. */
    fun activeSession(): SavedSession? {
        val sid = prefs.getString("sid", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val savedAt = prefs.getLong("saved_at", 0L)
        // LiveKit tokens are issued with a 6h TTL — don't resume a stale one.
        if (System.currentTimeMillis() - savedAt > 6 * 60 * 60 * 1000L) {
            clearActiveSession()
            return null
        }
        return SavedSession(
            sessionId = sid,
            token = token,
            liveKitUrl = prefs.getString("lkurl", "") ?: "",
            websocketUrl = prefs.getString("wsurl", "") ?: ""
        )
    }
}
