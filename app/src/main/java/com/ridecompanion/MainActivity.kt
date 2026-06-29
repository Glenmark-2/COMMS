package com.ridecompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ridecompanion.features.map.MapViewModel
import com.ridecompanion.features.session.SessionScreen
import com.ridecompanion.features.session.SessionViewModel
import com.ridecompanion.features.voice.VoiceViewModel
import com.ridecompanion.ui.RideDashboardScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val sessionViewModel: SessionViewModel by viewModels()
    private val mapViewModel: MapViewModel by viewModels()
    private val voiceViewModel: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    
                    NavHost(
                        navController = navController,
                        startDestination = "session"
                    ) {
                        composable("session") {
                            SessionScreen(
                                viewModel = sessionViewModel,
                                onNavigateToRideDashboard = { sessionId ->
                                    navController.navigate("ride")
                                }
                            )
                        }
                        
                        composable("ride") {
                            RideDashboardScreen(
                                mapViewModel = mapViewModel,
                                voiceViewModel = voiceViewModel,
                                sessionViewModel = sessionViewModel,
                                onLeaveRide = {
                                    navController.navigate("session") {
                                        popUpTo("session") { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
