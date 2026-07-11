package com.ridecompanion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ride Companion design system — one dark, high-contrast palette used by every
 * screen, tuned for sunlight readability on a handlebar-mounted phone.
 */
object RideColors {
    val Background = Color(0xFF060913)      // near-black blue
    val Surface = Color(0xFF10131F)         // cards, sheets
    val SurfaceHigh = Color(0xFF181C2C)     // elevated cards, inputs
    val Outline = Color(0x14FFFFFF)         // hairline borders
    val OutlineStrong = Color(0x29FFFFFF)

    val Primary = Color(0xFF00E5FF)         // cyan — actions, accents
    val OnPrimary = Color(0xFF00171A)
    val PrimaryDim = Color(0x3300E5FF)
    val PrimaryFaint = Color(0x1400E5FF)

    val TextPrimary = Color(0xFFF2F4FA)
    val TextSecondary = Color(0xFF8A93A8)
    val TextTertiary = Color(0xFF525B70)

    val Positive = Color(0xFF00E676)        // connected, live
    val Warning = Color(0xFFFFB74D)         // connecting, caution
    val Danger = Color(0xFFFF5252)          // SOS, leave, errors
    val DangerDim = Color(0x2EFF5252)
}

private val DarkColorScheme = darkColorScheme(
    primary = RideColors.Primary,
    onPrimary = RideColors.OnPrimary,
    secondary = RideColors.Primary,
    onSecondary = RideColors.OnPrimary,
    background = RideColors.Background,
    onBackground = RideColors.TextPrimary,
    surface = RideColors.Surface,
    onSurface = RideColors.TextPrimary,
    surfaceVariant = RideColors.SurfaceHigh,
    onSurfaceVariant = RideColors.TextSecondary,
    outline = RideColors.OutlineStrong,
    error = RideColors.Danger,
    onError = Color.White
)

private val RideTypography = Typography(
    // Big numerals (speed, distance) — tight and heavy, like a dashboard.
    displayLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 40.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    // Section labels — small caps feel via spacing.
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.8.sp)
)

private val RideShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = RideTypography,
        shapes = RideShapes,
        content = content
    )
}
