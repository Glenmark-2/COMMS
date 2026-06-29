package com.ridecompanion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridecompanion.features.map.MapScreen
import com.ridecompanion.features.map.MapViewModel
import com.ridecompanion.features.voice.VoiceViewModel
import com.ridecompanion.features.session.SessionViewModel

@Composable
fun RideDashboardScreen(
    mapViewModel: MapViewModel,
    voiceViewModel: VoiceViewModel,
    sessionViewModel: SessionViewModel,
    onLeaveRide: () -> Unit
) {
    val isMuted by voiceViewModel.isMuted.collectAsState()
    val speakingRiders by voiceViewModel.speakingRiders.collectAsState()
    val activeTransport by voiceViewModel.activeTransport.collectAsState()

    var isVoiceCardExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        MapScreen(viewModel = mapViewModel)

        Box(
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE60C0D14))
                .border(1.dp, Color(0x2Bffffff), RoundedCornerShape(20.dp))
                .clickable { isVoiceCardExpanded = !isVoiceCardExpanded }
                .padding(16.dp)
        ) {
            Column {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { voiceViewModel.toggleMute() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isMuted) Color(0x33FF5252) else Color(0x1F00E5FF))
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Voice State Icon",
                                tint = if (isMuted) Color(0xFFFF5252) else Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isMuted) "INTERCOM MUTED" else "INTERCOM ACTIVE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMuted) Color(0xFFFF5252) else Color(0xFF00E5FF)
                            )
                            val statusText = if (speakingRiders.isNotEmpty()) {
                                "Speaking: ${speakingRiders.joinToString()}"
                            } else {
                                "Active mode: ${activeTransport?.name ?: "OFFLINE"}"
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = Color(0xFF8A90A6)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            sessionViewModel.leaveSession()
                            onLeaveRide()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x1FFF5252))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneDisabled,
                            contentDescription = "Leave Ride Session",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isVoiceCardExpanded,
                    enter = slideInVertically { it / 2 },
                    exit = slideOutVertically { it / 2 }
                ) {
                    val volumeScale by voiceViewModel.volumeScale.collectAsState()
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        HorizontalDivider(color = Color(0x1Fffffff))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "INTERCOM VOLUME",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8A90A6),
                            letterSpacing = 1.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = volumeScale,
                                onValueChange = { voiceViewModel.adjustVolume(it) },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF00E5FF),
                                    activeTrackColor = Color(0xFF00E5FF),
                                    inactiveTrackColor = Color(0x15ffffff)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
