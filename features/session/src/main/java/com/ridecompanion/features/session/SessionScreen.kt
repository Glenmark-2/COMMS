package com.ridecompanion.features.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette — mirrors the app-wide RideColors design system.
private val Bg = Color(0xFF060913)
private val Surface = Color(0xFF10131F)
private val SurfaceHigh = Color(0xFF181C2C)
private val Outline = Color(0x14FFFFFF)
private val OutlineStrong = Color(0x29FFFFFF)
private val Cyan = Color(0xFF00E5FF)
private val OnCyan = Color(0xFF00171A)
private val TextSecondary = Color(0xFF8A93A8)
private val TextTertiary = Color(0xFF525B70)
private val Positive = Color(0xFF00E676)
private val Warning = Color(0xFFFFB74D)
private val Danger = Color(0xFFFF5252)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onNavigateToRideDashboard: (sessionId: String) -> Unit,
    onNavigateToHistory: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val isServerOnline by viewModel.isServerOnline.collectAsState()

    var riderName by remember { mutableStateOf(viewModel.savedRiderName) }
    var sessionName by remember { mutableStateOf("") }
    var sessionIdToJoin by remember { mutableStateOf("") }
    var isCreateMode by remember { mutableStateOf(true) }

    LaunchedEffect(uiState) {
        if (uiState is SessionUiState.Success) {
            onNavigateToRideDashboard((uiState as SessionUiState.Success).sessionId)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF0A0E1C), Bg)))
    ) {
        // Ambient glow behind the hero — depth without noise.
        Box(
            modifier = Modifier
                .size(340.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-120).dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x2200E5FF), Color.Transparent)),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Server status ----
            ServerStatusChip(isServerOnline) { viewModel.checkServerStatus() }

            Spacer(Modifier.weight(0.6f))

            // ---- Hero ----
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0x1400E5FF))
                    .border(1.dp, Color(0x3300E5FF), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DirectionsBike, contentDescription = null, tint = Cyan, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "RIDE COMPANION",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 3.sp
            )
            Text(
                text = "Group voice · live map · ride together",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.weight(0.5f))

            // ---- Form card ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Surface)
                    .border(1.dp, Outline, RoundedCornerShape(26.dp))
                    .padding(20.dp)
            ) {
                // Segmented Create / Join switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceHigh)
                        .padding(4.dp)
                ) {
                    SegmentButton("Create ride", isCreateMode, Modifier.weight(1f)) { isCreateMode = true }
                    SegmentButton("Join ride", !isCreateMode, Modifier.weight(1f)) { isCreateMode = false }
                }

                Spacer(Modifier.height(16.dp))

                RideTextField(
                    value = riderName,
                    onValueChange = { riderName = it.take(20) },
                    label = "Your name",
                    capitalization = KeyboardCapitalization.Words
                )

                Spacer(Modifier.height(12.dp))

                if (isCreateMode) {
                    RideTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it.take(30) },
                        label = "Ride name",
                        capitalization = KeyboardCapitalization.Sentences
                    )
                } else {
                    // Session codes are 6 uppercase alphanumerics — enforce as you type.
                    OutlinedTextField(
                        value = sessionIdToJoin,
                        onValueChange = { input ->
                            sessionIdToJoin = input.uppercase().filter { it.isLetterOrDigit() }.take(6)
                        },
                        label = { Text("Session code", color = TextSecondary) },
                        placeholder = { Text("ABC123", color = TextTertiary, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace) },
                        colors = fieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                            textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Go
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(Modifier.height(18.dp))

                val formReady = riderName.isNotBlank() &&
                    (if (isCreateMode) sessionName.isNotBlank() else sessionIdToJoin.length == 6)

                Button(
                    onClick = {
                        if (isCreateMode) viewModel.createSession(sessionName.trim(), riderName.trim())
                        else viewModel.joinSession(sessionIdToJoin, riderName.trim())
                    },
                    enabled = formReady && uiState !is SessionUiState.Loading && isServerOnline == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan,
                        disabledContainerColor = Color(0x1F00E5FF),
                        contentColor = OnCyan,
                        disabledContentColor = Color(0x4DFFFFFF)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    if (uiState is SessionUiState.Loading) {
                        CircularProgressIndicator(color = OnCyan, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            text = if (isCreateMode) "START RIDING" else "JOIN THE GROUP",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            fontSize = 15.sp
                        )
                    }
                }

                AnimatedVisibility(
                    visible = uiState is SessionUiState.Error,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val errorMsg = (uiState as? SessionUiState.Error)?.message ?: ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x2EFF5252))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(friendlyError(errorMsg), color = Danger, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- Footer ----
            TextButton(onClick = onNavigateToHistory) {
                Text("RIDE HISTORY", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, fontSize = 13.sp)
            }
            Text(
                text = "v1.0.0",
                fontSize = 11.sp,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color(0x2400E5FF) else Color.Transparent)
            .border(
                1.dp,
                if (selected) Color(0x4D00E5FF) else Color.Transparent,
                RoundedCornerShape(11.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Cyan else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun RideTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    capitalization: KeyboardCapitalization
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Cyan,
    unfocusedBorderColor = OutlineStrong,
    focusedContainerColor = SurfaceHigh,
    unfocusedContainerColor = SurfaceHigh,
    cursorColor = Cyan
)

@Composable
private fun ServerStatusChip(isServerOnline: Boolean?, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(20.dp))
            .clickable(onClick = onRetry)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val (statusText, statusColor, statusIcon) = when (isServerOnline) {
            true -> Triple("Online", Positive, Icons.Default.Cloud)
            false -> Triple("Offline — tap to retry", Danger, Icons.Default.CloudOff)
            null -> Triple("Checking…", Warning, Icons.Default.Refresh)
        }
        Icon(statusIcon, contentDescription = "Server status", tint = statusColor, modifier = Modifier.size(14.dp))
        Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor)
    }
}

/** Turn raw exception text into something a rider can act on. */
private fun friendlyError(raw: String): String = when {
    raw.contains("404") -> "That session code wasn't found — double-check it with your group."
    raw.contains("full", ignoreCase = true) || raw.contains("400") -> "That session is already full (3 riders max)."
    raw.contains("timeout", ignoreCase = true) ||
        raw.contains("Unable to resolve host", ignoreCase = true) -> "Can't reach the server — check your internet connection."
    raw.isBlank() -> "Something went wrong — please try again."
    else -> raw
}
