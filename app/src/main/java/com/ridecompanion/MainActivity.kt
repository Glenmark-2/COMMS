package com.ridecompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ridecompanion.features.map.MapViewModel
import com.ridecompanion.features.session.SessionScreen
import com.ridecompanion.features.session.SessionViewModel
import com.ridecompanion.features.voice.VoiceViewModel
import com.ridecompanion.ui.HistoryScreen
import com.ridecompanion.ui.HistoryViewModel
import com.ridecompanion.ui.RideDashboardScreen
import com.ridecompanion.ui.RideSummaryScreen
import com.ridecompanion.ui.theme.RideTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val sessionViewModel: SessionViewModel by viewModels()
    private val mapViewModel: MapViewModel by viewModels()
    private val voiceViewModel: VoiceViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        
        if (!fineLocationGranted || !recordAudioGranted) {
            Toast.makeText(
                this,
                "Location and Microphone permissions are required for tracking and intercom.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars — the map and dark screens fill the display.
        enableEdgeToEdge()
        checkAndRequestPermissions()
        requestBatteryOptimizationExemption()
        setContent {
            RideTheme {
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
                                },
                                onNavigateToHistory = {
                                    navController.navigate("history")
                                }
                            )
                        }

                        composable("ride") {
                            RideDashboardScreen(
                                mapViewModel = mapViewModel,
                                voiceViewModel = voiceViewModel,
                                sessionViewModel = sessionViewModel,
                                onLeaveRide = {
                                    navController.navigate("summary") {
                                        popUpTo("session")
                                    }
                                }
                            )
                        }

                        composable("summary") {
                            val mapState by mapViewModel.uiState.collectAsState()
                            RideSummaryScreen(
                                summary = mapState.lastRideSummary,
                                onDone = {
                                    mapViewModel.clearLastSummary()
                                    navController.navigate("session") {
                                        popUpTo("session") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("history") {
                            HistoryScreen(
                                viewModel = historyViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Ask Android to exempt the app from battery optimization (Doze). Without
     * this, many phones throttle GPS and network for a pocketed screen-off
     * phone — which would silence the intercom and freeze navigation mid-ride.
     */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // Some OEM builds hide this screen — the foreground service and
                // wake lock still keep the ride alive on those devices.
            }
        }
    }
}
