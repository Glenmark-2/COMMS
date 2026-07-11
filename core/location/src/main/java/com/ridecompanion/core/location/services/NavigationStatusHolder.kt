package com.ridecompanion.core.location.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared flag: true while turn-by-turn navigation is active. The foreground
 * location service uses it to switch to fast (1 s) GPS updates, which
 * turn-by-turn guidance needs to call turns on time.
 */
@Singleton
class NavigationStatusHolder @Inject constructor() {
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating

    fun setNavigating(navigating: Boolean) {
        _isNavigating.value = navigating
    }
}
