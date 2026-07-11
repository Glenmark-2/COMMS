package com.ridecompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ridecompanion.core.database.entity.RideSummaryEntity
import kotlin.math.roundToInt

@Composable
fun RideSummaryScreen(
    summary: RideSummaryEntity?,
    onDone: () -> Unit
) {
    // Nothing meaningful to show (very short ride) — skip straight out.
    LaunchedEffect(summary) {
        if (summary == null) onDone()
    }
    if (summary == null) return

    val c = com.ridecompanion.ui.theme.RideColors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "RIDE COMPLETE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = c.Primary,
                letterSpacing = 2.5.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary.name,
                fontSize = 15.sp,
                color = c.TextSecondary
            )
            Spacer(Modifier.height(28.dp))

            // Hero stat — the distance you rode.
            Text(
                text = formatKm(summary.distanceMeters),
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = (-1).sp
            )
            Text("DISTANCE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.TextTertiary, letterSpacing = 1.5.sp)

            Spacer(Modifier.height(28.dp))

            // 3-up stat tiles
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryTile("TIME", formatDurationMillis(summary.durationMillis), Modifier.weight(1f))
                SummaryTile("AVG", "${(summary.avgSpeedMps * 3.6f).roundToInt()}", Modifier.weight(1f), unit = "km/h")
                SummaryTile("MAX", "${(summary.maxSpeedMps * 3.6f).roundToInt()}", Modifier.weight(1f), unit = "km/h")
            }

            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.Primary,
                    contentColor = c.OnPrimary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text("DONE", fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    val c = com.ridecompanion.ui.theme.RideColors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.Surface)
            .border(1.dp, c.Outline, RoundedCornerShape(16.dp))
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
        if (unit != null) {
            Text(unit, fontSize = 10.sp, color = c.TextTertiary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.TextTertiary, letterSpacing = 1.2.sp)
    }
}

internal fun formatKm(meters: Double): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.roundToInt()} m"

internal fun formatDurationMillis(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
