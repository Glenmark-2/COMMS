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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                MapView(context).apply {
                    onCreate(null)
                    getMapAsync { mapboxMap ->
                        mapboxMap.setStyle("https://demotiles.maplibre.org/style.json")
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { mapView ->
            }
        )

        Box(
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE60C0D14))
                .border(1.dp, Color(0x33ffffff), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val speedKmh = uiState.currentRiderState?.speed?.let { (it * 3.6f).roundToInt() } ?: 0
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

        Column(
            modifier = Modifier
                .padding(16.dp)
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
                                text = "Bat: ${rider.batteryPercentage}%",
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

        AnimatedVisibility(
            visible = uiState.isOffRoute && uiState.snapResult != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(24.dp)
                .align(Alignment.BottomCenter)
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
                    contentDescription = "Rejoin Navigation Arrow",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(snap.bearingToRejoinDegrees - 90f) // Offset since PlayArrow points right (90deg) by default
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
