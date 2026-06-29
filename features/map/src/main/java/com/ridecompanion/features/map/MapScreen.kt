package com.ridecompanion.features.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isMapReady by remember { mutableStateOf(false) }
    var hasInitialZoom by remember { mutableStateOf(false) }

    // Update camera when location changes
    LaunchedEffect(uiState.currentLocation, isMapReady, uiState.isFollowingUser) {
        val location = uiState.currentLocation ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        if (!uiState.isFollowingUser && hasInitialZoom) return@LaunchedEffect

        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(if (hasInitialZoom) map.cameraPosition.zoom else 15.0)
            .bearing(location.bearing.toDouble())
            .tilt(if (hasInitialZoom) map.cameraPosition.tilt else 45.0)
            .build()

        if (!hasInitialZoom) {
            map.cameraPosition = cameraPosition
            hasInitialZoom = true
        } else {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Map
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    onCreate(null)
                    getMapAsync { map ->
                        mapLibreMap = map

                        // Load dark style from assets
                        val styleJson = ctx.assets.open("map_style.json")
                            .bufferedReader().use { it.readText() }

                        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                            isMapReady = true

                            // Enable location component (blue dot)
                            try {
                                map.locationComponent.apply {
                                    activateLocationComponent(
                                        org.maplibre.android.location.LocationComponentActivationOptions
                                            .builder(ctx, style)
                                            .useDefaultLocationEngine(true)
                                            .build()
                                    )
                                    isLocationComponentEnabled = true
                                    cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                                    renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
                                }
                            } catch (e: SecurityException) {
                                // Location permission not yet granted — that's okay,
                                // the dot won't show until permission is granted
                            }
                        }

                        // Detect user panning
                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                viewModel.setFollowingUser(false)
                            }
                        }

                        // Map UI settings
                        map.uiSettings.isCompassEnabled = false
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ -> }
        )

        // Speed indicator (top-left)
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 60.dp)
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE60C0D14))
                .border(1.dp, Color(0x33ffffff), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val speedKmh = uiState.currentLocation?.speed?.let { (it * 3.6f).roundToInt() } ?: 0
                Text(
                    text = "$speedKmh",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00E5FF)
                )
                Text(
                    text = "km/h",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8A90A6)
                )
            }
        }

        // Other riders panel (top-right)
        if (uiState.otherRiders.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(end = 16.dp, top = 60.dp)
                    .align(Alignment.TopEnd)
                    .width(140.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.otherRiders.forEach { rider ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xCC0C0D14))
                            .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text(
                                text = rider.userId.take(8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${rider.batteryPercentage}% 🔋",
                                    fontSize = 10.sp,
                                    color = Color(0xFF8A90A6)
                                )
                                val signalColor = if (rider.networkType.name == "OFFLINE") Color(0xFFFF5252) else Color(0xFF00E676)
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(signalColor)
                                        .align(Alignment.CenterVertically)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Recenter button
        AnimatedVisibility(
            visible = !uiState.isFollowingUser,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 160.dp)
        ) {
            IconButton(
                onClick = { viewModel.setFollowingUser(true) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xE600E5FF))
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter",
                    tint = Color(0xFF060913),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Off-route warning
        AnimatedVisibility(
            visible = uiState.isOffRoute && uiState.snapResult != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(24.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp)
        ) {
            val snap = uiState.snapResult ?: return@AnimatedVisibility
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE65100))
                    .border(1.dp, Color(0x7Fffffff), RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Rejoin Arrow",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(snap.bearingToRejoinDegrees - 90f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "OFF ROUTE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xCCffffff),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Rejoin in ${snap.distanceToRouteMeters.roundToInt()}m",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }
    }
}
