package com.ridecompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridecompanion.core.database.dao.RideDao
import com.ridecompanion.core.database.entity.RideSummaryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class HistoryViewModel @Inject constructor(
    rideDao: RideDao
) : ViewModel() {
    val rides: StateFlow<List<RideSummaryEntity>> =
        rideDao.getAllRideSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit
) {
    val rides by viewModel.rides.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.ridecompanion.ui.theme.RideColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("RIDE HISTORY", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 1.5.sp)
            }

            Spacer(Modifier.height(12.dp))

            if (rides.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.DirectionsBike,
                        contentDescription = null,
                        tint = com.ridecompanion.ui.theme.RideColors.TextTertiary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No rides yet", color = com.ridecompanion.ui.theme.RideColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Your finished rides will show up here.", color = com.ridecompanion.ui.theme.RideColors.TextTertiary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rides) { ride ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(com.ridecompanion.ui.theme.RideColors.Surface)
                                .border(1.dp, com.ridecompanion.ui.theme.RideColors.Outline, RoundedCornerShape(18.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(com.ridecompanion.ui.theme.RideColors.PrimaryFaint),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.DirectionsBike,
                                    contentDescription = null,
                                    tint = com.ridecompanion.ui.theme.RideColors.Primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ride.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${formatKm(ride.distanceMeters)} · ${formatDurationMillis(ride.durationMillis)} · ${(ride.avgSpeedMps * 3.6f).roundToInt()} km/h avg",
                                    fontSize = 12.sp,
                                    color = com.ridecompanion.ui.theme.RideColors.TextSecondary
                                )
                            }
                            Text(
                                formatKm(ride.distanceMeters),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                color = com.ridecompanion.ui.theme.RideColors.Primary
                            )
                        }
                    }
                }
            }
        }
    }
}
