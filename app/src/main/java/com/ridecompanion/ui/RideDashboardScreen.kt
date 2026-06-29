package com.ridecompanion.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridecompanion.features.map.MapScreen
import com.ridecompanion.features.map.MapViewModel
import com.ridecompanion.features.voice.VoiceViewModel
import com.ridecompanion.features.session.SessionViewModel
import com.ridecompanion.features.session.SessionUiState

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
    val sessionUiState by sessionViewModel.uiState.collectAsState()
    val mapUiState by mapViewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    // Derive session ID from state
    val sessionId = when (sessionUiState) {
        is SessionUiState.Success -> (sessionUiState as SessionUiState.Success).sessionId
        else -> "---"
    }

    // Pulse animation for active intercom
    val intercomPulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by intercomPulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    var isVoiceCardExpanded by remember { mutableStateOf(false) }
    var showCopiedToast by remember { mutableStateOf(false) }

    // Auto-dismiss copied toast
    LaunchedEffect(showCopiedToast) {
        if (showCopiedToast) {
            kotlinx.coroutines.delay(2000)
            showCopiedToast = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map
        MapScreen(viewModel = mapViewModel)

        // ====== TOP STATUS BAR ======
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Session ID chip (left)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC0C0D14))
                    .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(12.dp))
                    .clickable {
                        if (sessionId != "---") {
                            clipboardManager.setText(AnnotatedString(sessionId))
                            showCopiedToast = true
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "ID:",
                    fontSize = 10.sp,
                    color = Color(0xFF8A90A6),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = sessionId.take(8),
                    fontSize = 13.sp,
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Session ID",
                    tint = Color(0xFF8A90A6),
                    modifier = Modifier.size(14.dp)
                )
            }

            // Riders count + connection quality (right)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC0C0D14))
                    .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Riders count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = "Riders",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${mapUiState.otherRiders.size + 1}",
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Connection quality
                val connectionColor = when {
                    activeTransport != null -> Color(0xFF00E676)
                    else -> Color(0xFFFF5252)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = "Signal",
                        tint = connectionColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (activeTransport != null) "LIVE" else "OFFLINE",
                        fontSize = 10.sp,
                        color = connectionColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ====== COPIED TOAST ======
        AnimatedVisibility(
            visible = showCopiedToast,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF00E5FF))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Session ID copied!",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF060913)
                )
            }
        }

        // ====== BOTTOM VOICE CONTROL CARD ======
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .navigationBarsPadding()
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xF00C0D14), Color(0xFA080A12))
                    )
                )
                .border(1.dp, Color(0x2Bffffff), RoundedCornerShape(24.dp))
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
                        // Mic button with pulse
                        Box(contentAlignment = Alignment.Center) {
                            // Pulse ring when unmuted and connected
                            if (!isMuted && activeTransport != null) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .scale(pulseScale)
                                        .clip(CircleShape)
                                        .background(Color(0x1A00E5FF))
                                )
                            }
                            IconButton(
                                onClick = { voiceViewModel.toggleMute() },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isMuted) Color(0x33FF5252) else Color(0x2200E5FF)
                                    )
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Toggle Mute",
                                    tint = if (isMuted) Color(0xFFFF5252) else Color(0xFF00E5FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = when {
                                    activeTransport == null -> "INTERCOM CONNECTING…"
                                    isMuted -> "INTERCOM MUTED"
                                    else -> "INTERCOM ACTIVE"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    activeTransport == null -> Color(0xFFFFAB40)
                                    isMuted -> Color(0xFFFF5252)
                                    else -> Color(0xFF00E5FF)
                                },
                                letterSpacing = 1.sp
                            )
                            val statusText = if (speakingRiders.isNotEmpty()) {
                                "🗣️ ${speakingRiders.joinToString()}"
                            } else if (activeTransport != null) {
                                "Connected · ${activeTransport?.name ?: ""}"
                            } else {
                                "Establishing voice link…"
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = Color(0xFF8A90A6)
                            )
                        }
                    }

                    // Leave ride button
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
                            imageVector = Icons.Default.Close,
                            contentDescription = "Leave Ride",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Expandable volume section
                AnimatedVisibility(
                    visible = isVoiceCardExpanded,
                    enter = slideInVertically { it / 2 } + fadeIn(),
                    exit = slideOutVertically { it / 2 } + fadeOut()
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
                        Slider(
                            value = volumeScale,
                            onValueChange = { voiceViewModel.adjustVolume(it) },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color(0x15ffffff)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Leave ride button (full width in expanded mode)
                        Button(
                            onClick = {
                                sessionViewModel.leaveSession()
                                onLeaveRide()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x33FF5252),
                                contentColor = Color(0xFFFF5252)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "LEAVE RIDE",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
