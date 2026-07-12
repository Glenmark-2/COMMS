package com.ridecompanion.features.session

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.network.api.CreateSessionRequest
import com.ridecompanion.core.network.api.JoinSessionRequest
import com.ridecompanion.core.network.api.RideApi
import com.ridecompanion.core.network.transport.AdaptiveTransportManager
import com.ridecompanion.core.location.services.RideForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SessionUiState {
    data object Idle : SessionUiState
    data object Loading : SessionUiState
    data class Success(val sessionId: String, val websocketUrl: String) : SessionUiState
    data class Error(val message: String) : SessionUiState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rideApi: RideApi,
    private val transportManager: AdaptiveTransportManager,
    private val sessionStore: SessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
    val uiState: StateFlow<SessionUiState> = _uiState

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline

    /** Last-used rider name, to prefill the form. */
    val savedRiderName: String get() = sessionStore.riderName

    init {
        checkServerStatus()
        // Resume an interrupted ride (app was killed/restarted mid-ride).
        // Only restore state + transports here — the foreground service is
        // started by the dashboard once it's visible. Starting a microphone/
        // location service during cold start can be rejected by Android 14+
        // and wedge the whole launch.
        sessionStore.activeSession()?.let { saved ->
            transportManager.setLocalRiderName(sessionStore.riderName)
            runCatching { transportManager.start(saved.sessionId, saved.token, saved.liveKitUrl) }
            _uiState.value = SessionUiState.Success(saved.sessionId, saved.websocketUrl)
        }
    }

    /**
     * Start (or re-assert) the ride foreground service. Idempotent; called by
     * the ride dashboard when it becomes visible, when the app is safely in
     * the foreground.
     */
    fun ensureServiceRunning() {
        val sessionId = (_uiState.value as? SessionUiState.Success)?.sessionId ?: return
        val serviceIntent = Intent(context, RideForegroundService::class.java).apply {
            action = RideForegroundService.ACTION_START
            putExtra(RideForegroundService.EXTRA_SESSION_ID, sessionId)
        }
        runCatching { context.startForegroundService(serviceIntent) }
    }

    fun checkServerStatus() {
        _isServerOnline.value = null
        viewModelScope.launch {
            try {
                rideApi.checkHealth()
                _isServerOnline.value = true
            } catch (e: Exception) {
                _isServerOnline.value = false
            }
        }
    }

    fun createSession(sessionName: String, riderName: String) {
        _uiState.value = SessionUiState.Loading
        transportManager.setLocalRiderName(riderName)
        viewModelScope.launch {
            try {
                val response = rideApi.createSession(
                    CreateSessionRequest(sessionName, riderName)
                )
                startSessionServices(
                    sessionId = response.sessionId,
                    token = response.token,
                    url = response.liveKitUrl
                )
                persistSession(response, riderName)
                _uiState.value = SessionUiState.Success(response.sessionId, response.websocketUrl)
            } catch (e: Exception) {
                _uiState.value = SessionUiState.Error(e.message ?: "Failed to create session")
            }
        }
    }

    fun joinSession(sessionId: String, riderName: String) {
        _uiState.value = SessionUiState.Loading
        transportManager.setLocalRiderName(riderName)
        viewModelScope.launch {
            try {
                val response = rideApi.joinSession(
                    JoinSessionRequest(sessionId, riderName)
                )
                startSessionServices(
                    sessionId = response.sessionId,
                    token = response.token,
                    url = response.liveKitUrl
                )
                persistSession(response, riderName)
                _uiState.value = SessionUiState.Success(response.sessionId, response.websocketUrl)
            } catch (e: Exception) {
                _uiState.value = SessionUiState.Error(e.message ?: "Failed to join session")
            }
        }
    }

    private fun persistSession(
        response: com.ridecompanion.core.network.api.SessionResponse,
        riderName: String
    ) {
        sessionStore.saveActiveSession(
            SavedSession(
                sessionId = response.sessionId,
                token = response.token,
                liveKitUrl = response.liveKitUrl,
                websocketUrl = response.websocketUrl
            ),
            riderName = riderName
        )
    }

    private fun startSessionServices(sessionId: String, token: String, url: String) {
        runCatching { transportManager.start(sessionId, token, url) }

        val serviceIntent = Intent(context, RideForegroundService::class.java).apply {
            action = RideForegroundService.ACTION_START
            putExtra(RideForegroundService.EXTRA_SESSION_ID, sessionId)
        }
        // A rejected start (rare OEM background quirks) must never crash the
        // app — the dashboard re-asserts the service when it becomes visible.
        runCatching { context.startForegroundService(serviceIntent) }
    }

    fun leaveSession() {
        transportManager.stop()
        sessionStore.clearActiveSession()
        val serviceIntent = Intent(context, RideForegroundService::class.java).apply {
            action = RideForegroundService.ACTION_STOP
        }
        context.startService(serviceIntent)
        _uiState.value = SessionUiState.Idle
    }
}
