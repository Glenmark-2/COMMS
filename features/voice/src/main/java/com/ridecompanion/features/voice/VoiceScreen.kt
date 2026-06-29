package com.ridecompanion.features.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridecompanion.core.network.transport.TransportType

@Composable
fun VoiceScreen(viewModel: VoiceViewModel) {
    val activeTransport by viewModel.activeTransport.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val volumeScale by viewModel.volumeScale.collectAsState()
    val speakingRiders by viewModel.speakingRiders.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF07080F), Color(0xFF030305))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x0Affffff))
                .border(1.dp, Color(0x0Dffffff), RoundedCornerShape(28.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "VOICE INTERCOM",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.5.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            val (badgeText, badgeColor) = when (activeTransport) {
                TransportType.CELLULAR_LIVEKIT -> "CELLULAR (HIGH QUALITY)" to Color(0xFF00E5FF)
                TransportType.LOCAL_P2P -> "LOCAL P2P MESH" to Color(0xFF00E676)
                null -> "OFFLINE / DISCONNECTED" to Color(0xFFFF5252)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(badgeColor.copy(alpha = 0.1f))
                    .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                val isActive = !isMuted && activeTransport != null
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .scale(pulseScale)
                            .background(Color(0x0600E5FF), shape = CircleShape)
                            .border(1.dp, Color(0x1F00E5FF), shape = CircleShape)
                    )
                }

                IconButton(
                    onClick = { viewModel.toggleMute() },
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(
                            if (isMuted) Color(0x1FFF5252) else Color(0xFF00E5FF)
                        )
                        .border(1.dp, if (isMuted) Color(0x3DFF5252) else Color(0x7Fffffff), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute Toggle",
                        tint = if (isMuted) Color(0xFFFF5252) else Color(0xFF07080F),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isMuted) "MIC MUTED" else "TAP TO MUTE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isMuted) Color(0xFFFF5252) else Color(0xFF8A90A6),
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Volume Icon",
                    tint = Color(0xFF8A90A6),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Slider(
                    value = volumeScale,
                    onValueChange = { viewModel.adjustVolume(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = Color(0x1Fffffff)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            if (volumeScale < 0.5f && !isMuted) {
                Text(
                    text = "Ducking Active (Navigation announcement playing)",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
