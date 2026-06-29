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
    private val transportManager: AdaptiveTransportManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
    val uiState: StateFlow<SessionUiState> = _uiState

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline

    init {
        checkServerStatus()
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
                _uiState.value = SessionUiState.Success(response.sessionId, response.websocketUrl)
            } catch (e: Exception) {
                _uiState.value = SessionUiState.Error(e.message ?: "Failed to create session")
            }
        }
    }

    fun joinSession(sessionId: String, riderName: String) {
        _uiState.value = SessionUiState.Loading
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
                _uiState.value = SessionUiState.Success(response.sessionId, response.websocketUrl)
            } catch (e: Exception) {
                _uiState.value = SessionUiState.Error(e.message ?: "Failed to join session")
            }
        }
    }

    private fun startSessionServices(sessionId: String, token: String, url: String) {
        transportManager.start(sessionId, token, url)

        val serviceIntent = Intent(context, RideForegroundService::class.java).apply {
            action = RideForegroundService.ACTION_START
            putExtra(RideForegroundService.EXTRA_SESSION_ID, sessionId)
        }
        context.startForegroundService(serviceIntent)
    }

    fun leaveSession() {
        transportManager.stop()
        val serviceIntent = Intent(context, RideForegroundService::class.java).apply {
            action = RideForegroundService.ACTION_STOP
        }
        context.startService(serviceIntent)
        _uiState.value = SessionUiState.Idle
    }
}
