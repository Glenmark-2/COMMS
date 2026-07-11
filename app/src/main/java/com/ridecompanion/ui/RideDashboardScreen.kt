package com.ridecompanion.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridecompanion.core.common.model.PlaceResult
import com.ridecompanion.core.common.model.RiderState
import com.ridecompanion.core.common.model.RouteProfile
import com.ridecompanion.core.navigation.engine.TurnDirection
import com.ridecompanion.core.network.transport.TransportType
import com.ridecompanion.features.map.MapScreen
import com.ridecompanion.features.map.MapViewModel
import com.ridecompanion.features.session.SessionUiState
import com.ridecompanion.features.session.SessionViewModel
import com.ridecompanion.features.voice.VoiceViewModel
import com.ridecompanion.ui.theme.RideColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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

    val sessionId = (sessionUiState as? SessionUiState.Success)?.sessionId ?: "----"

    var showSearchSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSosConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current

    // Keep the screen awake while riding (mounted on the handlebars).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 1-second ticker for elapsed ride time.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    val navigating = mapUiState.routePoints.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map
        MapScreen(viewModel = mapViewModel)

        // ===== TOP: the single most important thing — where to go next =====
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            when {
                mapUiState.isOffRoute && mapUiState.snapResult != null -> {
                    OffRouteBanner(
                        bearing = mapUiState.snapResult!!.bearingToRejoinDegrees,
                        rejoinMeters = mapUiState.snapResult!!.distanceToRouteMeters,
                        rerouting = mapUiState.isRoutingInProgress
                    )
                }
                navigating -> {
                    TurnBanner(
                        direction = mapUiState.nextTurnDirection,
                        thenDirection = mapUiState.thenTurnDirection,
                        turnDistanceMeters = mapUiState.nextTurnDistanceMeters,
                        remainingMeters = mapUiState.remainingDistanceMeters,
                        totalMeters = mapUiState.routeDistanceMeters,
                        destinationName = mapUiState.destinationName ?: "Destination",
                        streetName = mapUiState.nextTurnStreetName,
                        profile = mapUiState.routeProfile
                    )
                }
                else -> {
                    WhereToBar(
                        loading = mapUiState.isRoutingInProgress,
                        onClick = { showSearchSheet = true }
                    )
                }
            }
        }

        // ===== BOTTOM: pin card + speaking chip + dashboard bar =====
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val pin = mapUiState.droppedPin
            AnimatedVisibility(
                visible = pin != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                if (pin != null) {
                    DroppedPinCard(
                        pin = pin,
                        distanceMeters = mapViewModel.distanceToPoint(pin.latitude, pin.longitude),
                        routing = mapUiState.isRoutingInProgress,
                        onRide = { mapViewModel.rideToPin() },
                        onDismiss = { mapViewModel.dismissPin() }
                    )
                }
            }

            AnimatedVisibility(
                visible = speakingRiders.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                SpeakingChip(names = speakingRiders)
            }

            AnimatedVisibility(
                visible = activeTransport == null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                ReconnectingChip()
            }

            NavBottomBar(
                speedKmh = mapUiState.currentLocation?.speed?.let { (it * 3.6f).roundToInt() } ?: 0,
                navigating = navigating,
                remainingMeters = mapUiState.remainingDistanceMeters,
                etaSeconds = etaSeconds(
                    remainingMeters = mapUiState.remainingDistanceMeters,
                    smoothedSpeedMps = mapUiState.smoothedSpeedMps,
                    routeDistanceMeters = mapUiState.routeDistanceMeters,
                    routeDurationSeconds = mapUiState.routeDurationSeconds
                ),
                rideDistanceMeters = mapUiState.rideDistanceMeters,
                elapsedMillis = if (mapUiState.rideStartTimeMillis > 0) nowMillis - mapUiState.rideStartTimeMillis else 0L,
                isMuted = isMuted,
                connected = activeTransport != null,
                anyoneSpeaking = speakingRiders.isNotEmpty(),
                onToggleMute = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    voiceViewModel.toggleMute()
                },
                onOpenMenu = { showMenu = true }
            )
        }

        // ===== Destination search =====
        if (showSearchSheet) {
            DestinationSearchSheet(
                isSearching = mapUiState.isSearching,
                results = mapUiState.searchResults,
                recents = mapUiState.recentDestinations,
                routeError = mapUiState.routeError,
                distanceTo = { lat, lon -> mapViewModel.distanceToPoint(lat, lon) },
                onQueryChange = { mapViewModel.searchPlaces(it) },
                onSelect = { place ->
                    mapViewModel.selectDestination(place)
                    showSearchSheet = false
                },
                onDismiss = {
                    showSearchSheet = false
                    mapViewModel.clearSearch()
                }
            )
        }

        // ===== Ride menu (everything secondary lives here) =====
        if (showMenu) {
            RideMenuSheet(
                sessionId = sessionId,
                transport = activeTransport,
                riders = mapUiState.otherRiders,
                volume = voiceViewModel.volumeScale.collectAsState().value,
                hasDestination = navigating,
                destinationName = mapUiState.destinationName,
                distanceTo = { lat, lon -> mapViewModel.distanceToPoint(lat, lon) },
                onVolumeChange = { voiceViewModel.adjustVolume(it) },
                onClearDestination = {
                    mapViewModel.clearDestination()
                    showMenu = false
                },
                onSos = {
                    showMenu = false
                    showSosConfirm = true
                },
                onLeave = {
                    showMenu = false
                    showLeaveConfirm = true
                },
                onDismiss = { showMenu = false }
            )
        }

        // ===== Manual SOS confirm =====
        if (showSosConfirm) {
            AlertDialog(
                onDismissRequest = { showSosConfirm = false },
                confirmButton = {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        mapViewModel.sendSos()
                        showSosConfirm = false
                    }) { Text("SEND SOS", color = RideColors.Danger, fontWeight = FontWeight.Black) }
                },
                dismissButton = {
                    TextButton(onClick = { showSosConfirm = false }) {
                        Text("Cancel", color = RideColors.TextSecondary)
                    }
                },
                title = { Text("Send SOS to your group?") },
                text = { Text("This alerts everyone in your ride that you need help and shares your location.") }
            )
        }

        // ===== Leave confirm =====
        if (showLeaveConfirm) {
            AlertDialog(
                onDismissRequest = { showLeaveConfirm = false },
                confirmButton = {
                    TextButton(onClick = {
                        showLeaveConfirm = false
                        sessionViewModel.leaveSession()
                        mapViewModel.finishRide()
                        onLeaveRide()
                    }) { Text("LEAVE RIDE", color = RideColors.Danger, fontWeight = FontWeight.Black) }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveConfirm = false }) {
                        Text("Stay", color = RideColors.TextSecondary)
                    }
                },
                title = { Text("Leave this ride?") },
                text = { Text("You'll disconnect from the intercom and stop sharing your location with the group.") }
            )
        }

        // ===== Incoming SOS — full-screen, on top of everything =====
        val sos = mapUiState.activeSos
        if (sos != null) {
            SosOverlay(
                riderName = sos.riderId,
                reason = sos.reason,
                distanceMeters = mapViewModel.distanceToSos(sos),
                onAcknowledge = { mapViewModel.dismissSos() }
            )
        }
    }
}

// ============================ TOP BANNERS ============================

@Composable
private fun TurnBanner(
    direction: TurnDirection?,
    thenDirection: TurnDirection?,
    turnDistanceMeters: Double,
    remainingMeters: Double,
    totalMeters: Double,
    destinationName: String,
    streetName: String?,
    profile: RouteProfile
) {
    val arriving = direction == null
    val icon = if (arriving) Icons.Default.Flag else turnIcon(direction!!)
    val bigText = if (arriving) formatDistance(remainingMeters) else formatDistance(turnDistanceMeters)
    // "Turn left · Osmeña Ave" when the road is named, plain "Turn left" otherwise.
    val instruction = when {
        arriving -> "Continue to $destinationName"
        streetName != null -> "${turnText(direction!!)} · $streetName"
        else -> turnText(direction!!)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF50C0F1D))
            .border(1.dp, RideColors.Outline, RoundedCornerShape(20.dp))
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction glyph in an accent tile — reads at a glance in sunlight.
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(RideColors.PrimaryFaint)
                    .border(1.dp, RideColors.PrimaryDim, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = instruction, tint = RideColors.Primary, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(bigText, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                Text(instruction, color = RideColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            // "Then" preview — what comes right after this turn.
            if (!arriving && thenDirection != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("THEN", color = RideColors.TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(2.dp))
                    Icon(
                        turnIcon(thenDirection),
                        contentDescription = turnText(thenDirection),
                        tint = RideColors.TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        // Non-bike fallback: the rider should know why the route looks odd.
        if (profile != RouteProfile.BIKE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x26FFB300))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (profile == RouteProfile.FOOT) Icons.AutoMirrored.Filled.DirectionsWalk
                    else Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = RideColors.Warning,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    if (profile == RouteProfile.FOOT) "No bike route found — following a walking route"
                    else "No bike route found — following roads, ride carefully",
                    color = RideColors.Warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        // Route progress — thin, quiet, always advancing.
        if (totalMeters > 0) {
            val progress = ((totalMeters - remainingMeters) / totalMeters).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = RideColors.Primary,
                trackColor = Color(0x14FFFFFF),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt
            )
        }
    }
}

@Composable
private fun OffRouteBanner(bearing: Float, rejoinMeters: Double, rerouting: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF5B34700))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Straight,
            contentDescription = "Direction back to route",
            tint = Color.White,
            modifier = Modifier.size(42.dp).rotate(bearing)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("OFF ROUTE", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("Rejoin in ${rejoinMeters.roundToInt()} m", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
        }
        if (rerouting) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(4.dp))
                Text("Rerouting", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WhereToBar(loading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xF20C0F1D))
            .border(1.dp, RideColors.Outline, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = RideColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Text("Finding the best cycling route…", color = RideColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        } else {
            Icon(Icons.Default.Search, contentDescription = null, tint = RideColors.Primary, modifier = Modifier.size(22.dp))
            Text("Where to?", color = RideColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================ DROPPED PIN CARD ============================

@Composable
private fun DroppedPinCard(
    pin: PlaceResult,
    distanceMeters: Double?,
    routing: Boolean,
    onRide: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF50C0F1D))
            .border(1.dp, RideColors.Outline, RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(RideColors.PrimaryFaint),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Place, contentDescription = null, tint = RideColors.Primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pin.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            val detail = buildString {
                if (distanceMeters != null) append(formatDistance(distanceMeters))
                if (pin.description.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(pin.description)
                }
            }
            if (detail.isNotBlank()) {
                Text(detail, color = RideColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onRide,
            enabled = !routing,
            colors = ButtonDefaults.buttonColors(
                containerColor = RideColors.Primary,
                contentColor = Color(0xFF06212A)
            ),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (routing) {
                CircularProgressIndicator(color = Color(0xFF06212A), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Text("RIDE HERE", fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 0.5.sp)
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss pin", tint = RideColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

// ============================ BOTTOM BAR ============================

@Composable
private fun ReconnectingChip() {
    Row(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE03A2A00))
            .border(1.dp, Color(0x47FFB300), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(color = RideColors.Warning, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        Text("Intercom reconnecting…", color = RideColors.Warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpeakingChip(names: List<String>) {
    val pulse = rememberInfiniteTransition(label = "speak-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "speak-alpha"
    )
    Row(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE0003D44))
            .border(1.dp, RideColors.PrimaryDim, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(RideColors.Primary)
        )
        Text(
            text = names.joinToString(", "),
            color = RideColors.Primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text("speaking", color = Color(0x9900E5FF), fontSize = 12.sp)
    }
}

@Composable
private fun NavBottomBar(
    speedKmh: Int,
    navigating: Boolean,
    remainingMeters: Double,
    etaSeconds: Double,
    rideDistanceMeters: Double,
    elapsedMillis: Long,
    isMuted: Boolean,
    connected: Boolean,
    anyoneSpeaking: Boolean,
    onToggleMute: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF50C0F1D))
            .border(1.dp, RideColors.Outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speed — the number a rider glances at most.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$speedKmh", color = RideColors.Primary, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            Text("km/h", color = RideColors.TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }

        Spacer(Modifier.width(14.dp))
        Box(Modifier.height(36.dp).width(1.dp).background(RideColors.Outline))
        Spacer(Modifier.width(14.dp))

        // Primary metric
        Column(modifier = Modifier.weight(1f)) {
            if (navigating) {
                Text(formatDistance(remainingMeters), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    "${formatDuration(etaSeconds)} · arrive ${arrivalClock(etaSeconds)}",
                    color = RideColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            } else {
                Text(formatDistance(rideDistanceMeters), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(formatElapsed(elapsedMillis), color = RideColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Mic — large target, glove-friendly, with live connection dot.
        val micPulse = rememberInfiniteTransition(label = "mic-pulse")
        val micScale by micPulse.animateFloat(
            initialValue = 1f, targetValue = if (anyoneSpeaking && !isMuted) 1.08f else 1f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "mic-scale"
        )
        Box(contentAlignment = Alignment.TopEnd) {
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(48.dp)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(if (isMuted) RideColors.DangerDim else RideColors.PrimaryFaint)
                    .border(1.dp, if (isMuted) Color(0x47FF5252) else RideColors.PrimaryDim, CircleShape)
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isMuted) "Unmute intercom" else "Mute intercom",
                    tint = if (isMuted) RideColors.Danger else RideColors.Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) RideColors.Positive else RideColors.Warning)
                    .border(2.dp, Color(0xFF0C0F1D), CircleShape)
            )
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = onOpenMenu,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x14FFFFFF))
        ) {
            Icon(Icons.Default.Tune, contentDescription = "Ride menu", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ============================ RIDE MENU SHEET ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideMenuSheet(
    sessionId: String,
    transport: TransportType?,
    riders: List<RiderState>,
    volume: Float,
    hasDestination: Boolean,
    destinationName: String?,
    distanceTo: (Double, Double) -> Double?,
    onVolumeChange: (Float) -> Unit,
    onClearDestination: () -> Unit,
    onSos: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var codeCopied by remember { mutableStateOf(false) }

    LaunchedEffect(codeCopied) {
        if (codeCopied) {
            kotlinx.coroutines.delay(1500)
            codeCopied = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RideColors.Surface,
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            // ---- Session code + status ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(RideColors.SurfaceHigh)
                    .border(1.dp, RideColors.Outline, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SESSION CODE", color = RideColors.TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sessionId,
                        color = RideColors.Primary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    TransportPill(transport)
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(sessionId))
                    codeCopied = true
                }) {
                    Icon(
                        if (codeCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy session code",
                        tint = if (codeCopied) RideColors.Positive else RideColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join my ride on Ride Companion! Session code: $sessionId")
                    }
                    context.startActivity(Intent.createChooser(send, "Invite riders"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Invite riders", tint = RideColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- Riders ----
            SectionLabel("RIDERS (${riders.size + 1})")
            Spacer(Modifier.height(6.dp))
            if (riders.isEmpty()) {
                Text(
                    "No one else yet — share the code to invite your group.",
                    color = RideColors.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                riders.forEach { rider ->
                    RiderRow(rider = rider, distanceMeters = distanceTo(rider.latitude, rider.longitude))
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- Intercom volume ----
            SectionLabel("INTERCOM VOLUME")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = RideColors.TextTertiary, modifier = Modifier.size(18.dp))
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = RideColors.Primary,
                        activeTrackColor = RideColors.Primary,
                        inactiveTrackColor = Color(0x1FFFFFFF)
                    )
                )
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = RideColors.TextTertiary, modifier = Modifier.size(18.dp))
            }

            if (hasDestination) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(RideColors.SurfaceHigh)
                        .clickable(onClick = onClearDestination)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = RideColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("End navigation", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        destinationName?.let {
                            Text(it, color = RideColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- SOS + Leave ----
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSos,
                    colors = ButtonDefaults.buttonColors(containerColor = RideColors.DangerDim, contentColor = RideColors.Danger),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SOS", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
                Button(
                    onClick = onLeave,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x14FFFFFF), contentColor = RideColors.TextPrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("LEAVE RIDE", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun TransportPill(transport: TransportType?) {
    val (label, color) = when (transport) {
        TransportType.CELLULAR_LIVEKIT -> "INTERCOM LIVE · CLOUD" to RideColors.Positive
        TransportType.LOCAL_P2P -> "INTERCOM LIVE · DIRECT P2P" to RideColors.Positive
        null -> "CONNECTING…" to RideColors.Warning
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun RiderRow(rider: RiderState, distanceMeters: Double?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Presence dot
        val online = rider.networkType.name != "OFFLINE"
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .background(if (online) RideColors.Positive else RideColors.Danger)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            rider.userId.take(14),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (distanceMeters != null) {
            Text(formatDistance(distanceMeters), color = RideColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
        }
        val batteryColor = when {
            rider.batteryPercentage <= 15 -> RideColors.Danger
            rider.batteryPercentage <= 30 -> RideColors.Warning
            else -> RideColors.TextSecondary
        }
        Text("${rider.batteryPercentage}%", color = batteryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = RideColors.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x33FFFFFF))
        )
    }
}

// ============================ SEARCH SHEET ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationSearchSheet(
    isSearching: Boolean,
    results: List<PlaceResult>,
    recents: List<PlaceResult>,
    routeError: String?,
    distanceTo: (Double, Double) -> Double?,
    onQueryChange: (String) -> Unit,
    onSelect: (PlaceResult) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(query) {
        kotlinx.coroutines.delay(350)
        onQueryChange(query)
        if (query.trim().length >= 2) hasSearched = true
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RideColors.Surface,
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .imePadding()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search a place, address, or town", color = RideColors.TextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = RideColors.Primary) },
                trailingIcon = {
                    when {
                        isSearching -> CircularProgressIndicator(
                            color = RideColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)
                        )
                        query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = RideColors.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = RideColors.Primary,
                    unfocusedBorderColor = RideColors.OutlineStrong,
                    focusedContainerColor = RideColors.SurfaceHigh,
                    unfocusedContainerColor = RideColors.SurfaceHigh,
                    cursorColor = RideColors.Primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )

            if (routeError != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RideColors.DangerDim)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = RideColors.Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(routeError, color = RideColors.Danger, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            val showingRecents = query.trim().length < 2
            val list = if (showingRecents) recents else results

            if (showingRecents && recents.isNotEmpty()) {
                SectionLabel("RECENT")
                Spacer(Modifier.height(4.dp))
            }

            when {
                list.isNotEmpty() -> {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(list) { place ->
                            PlaceRow(
                                place = place,
                                isRecent = showingRecents,
                                distanceMeters = distanceTo(place.latitude, place.longitude),
                                onClick = { onSelect(place) }
                            )
                            HorizontalDivider(color = RideColors.Outline)
                        }
                    }
                }
                showingRecents -> {
                    EmptySearchHint("Search anywhere to ride to", "Towns, restaurants, gas stations, viewpoints…")
                }
                !isSearching && hasSearched -> {
                    EmptySearchHint("No places found", "Check the spelling, or try a nearby town name.")
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    place: PlaceResult,
    isRecent: Boolean,
    distanceMeters: Double?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(RideColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isRecent) Icons.Default.History else placeIcon(place.category),
                contentDescription = null,
                tint = RideColors.TextSecondary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (place.description.isNotBlank()) {
                Text(place.description, fontSize = 12.sp, color = RideColors.TextSecondary, maxLines = 1)
            }
        }
        if (distanceMeters != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatDistance(distanceMeters),
                fontSize = 12.sp, color = RideColors.TextTertiary, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptySearchHint(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Place, contentDescription = null, tint = RideColors.TextTertiary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = RideColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = RideColors.TextTertiary, fontSize = 12.sp)
    }
}

// ============================ SOS OVERLAY ============================

@Composable
private fun SosOverlay(
    riderName: String,
    reason: String,
    distanceMeters: Double?,
    onAcknowledge: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "sos-pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "sos-scale"
    )
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xF0B71C1C)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(
                Icons.Default.Warning, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(76.dp).scale(scale)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (reason == "CRASH") "POSSIBLE CRASH" else "SOS ALERT",
                color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("$riderName needs help", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (distanceMeters != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatDistance(distanceMeters)} away",
                    color = Color(0xFFFFCDD2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(52.dp)
            ) {
                Text("  ACKNOWLEDGE  ", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

// ============================ HELPERS ============================

private fun turnIcon(direction: TurnDirection): ImageVector = when (direction) {
    TurnDirection.LEFT -> Icons.Default.TurnLeft
    TurnDirection.SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
    TurnDirection.SHARP_LEFT -> Icons.Default.TurnSharpLeft
    TurnDirection.RIGHT -> Icons.Default.TurnRight
    TurnDirection.SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
    TurnDirection.SHARP_RIGHT -> Icons.Default.TurnSharpRight
    TurnDirection.UTURN -> Icons.Default.UTurnLeft
    TurnDirection.ROUNDABOUT -> Icons.Default.RoundaboutRight
    TurnDirection.STRAIGHT -> Icons.Default.Straight
}

private fun turnText(direction: TurnDirection): String = when (direction) {
    TurnDirection.LEFT -> "Turn left"
    TurnDirection.SLIGHT_LEFT -> "Slight left"
    TurnDirection.SHARP_LEFT -> "Sharp left"
    TurnDirection.RIGHT -> "Turn right"
    TurnDirection.SLIGHT_RIGHT -> "Slight right"
    TurnDirection.SHARP_RIGHT -> "Sharp right"
    TurnDirection.UTURN -> "Make a U-turn"
    TurnDirection.ROUNDABOUT -> "Roundabout"
    TurnDirection.STRAIGHT -> "Continue straight"
}

/** Icon for a search result based on its OSM classification. */
private fun placeIcon(category: String?): ImageVector {
    val c = category ?: return Icons.Default.Place
    return when {
        c.startsWith("place/") -> Icons.Default.LocationCity
        c == "amenity/restaurant" || c == "amenity/fast_food" || c == "amenity/food_court" -> Icons.Default.Restaurant
        c == "amenity/cafe" -> Icons.Default.LocalCafe
        c == "amenity/fuel" -> Icons.Default.LocalGasStation
        c == "amenity/hospital" || c == "amenity/clinic" || c == "amenity/pharmacy" -> Icons.Default.LocalHospital
        c.startsWith("amenity/") && c.contains("school") || c.startsWith("amenity/university") -> Icons.Default.School
        c.startsWith("shop/") -> Icons.Default.Store
        c.startsWith("tourism/") || c.startsWith("leisure/") -> Icons.Default.Attractions
        c.startsWith("natural/") || c == "landuse/forest" -> Icons.Default.Terrain
        else -> Icons.Default.Place
    }
}

private fun etaSeconds(
    remainingMeters: Double,
    smoothedSpeedMps: Float,
    routeDistanceMeters: Double,
    routeDurationSeconds: Double
): Double {
    // Prefer the rider's actual (smoothed) pace; while stopped at a light,
    // fall back to the route's planned pace so the ETA doesn't explode.
    val plannedSpeed = if (routeDurationSeconds > 0) routeDistanceMeters / routeDurationSeconds else 0.0
    val speed = when {
        smoothedSpeedMps >= 1.5f -> smoothedSpeedMps.toDouble()
        plannedSpeed > 0.5 -> plannedSpeed
        else -> 3.5 // ~12.6 km/h, a modest cycling pace
    }
    return remainingMeters / speed
}

private fun arrivalClock(etaSeconds: Double): String {
    val arrival = Date(System.currentTimeMillis() + (etaSeconds * 1000).toLong())
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrival)
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.roundToInt()} m"
}

private fun formatDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    return if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "$totalMinutes min"
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
