package com.shimonhoter.waze2coordinate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ===== Light: warm off-white, charcoal text, deep-blue primary =====
private val LightColors = lightColorScheme(
    primary          = Color(0xFF1E56A0),   // rich navy-blue
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001945),

    secondary        = Color(0xFF0D7A5F),   // deep teal-green for success/send
    onSecondary      = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB3F0DD),

    error            = Color(0xFFBA1A1A),
    onError          = Color(0xFFFFFFFF),
    errorContainer   = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background       = Color(0xFFFAF8F4),   // warm off-white
    onBackground     = Color(0xFF1C1B18),

    surface          = Color(0xFFFFFEFB),   // slightly warmer white
    onSurface        = Color(0xFF1C1B18),
    surfaceVariant   = Color(0xFFEEEBE4),   // warm light-grey for inputs/chips
    onSurfaceVariant = Color(0xFF4D4B44),

    outline          = Color(0xFFCEC8BC),
    outlineVariant   = Color(0xFFE5DFD5),
)

// ===== Dark: charcoal base, muted-navy primary, clean text =====
private val DarkColors = darkColorScheme(
    primary          = Color(0xFF8BB4F8),   // soft blue — readable on dark
    onPrimary        = Color(0xFF002D6E),
    primaryContainer = Color(0xFF0D3D8B),
    onPrimaryContainer = Color(0xFFD6E4FF),

    secondary        = Color(0xFF67D6AC),   // bright teal
    onSecondary      = Color(0xFF00382A),
    secondaryContainer = Color(0xFF005140),

    error            = Color(0xFFFFB4AB),
    onError          = Color(0xFF690005),
    errorContainer   = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background       = Color(0xFF131210),   // near-black, slightly warm
    onBackground     = Color(0xFFE8E2D9),

    surface          = Color(0xFF1E1C1A),   // dark warm charcoal
    onSurface        = Color(0xFFE8E2D9),
    surfaceVariant   = Color(0xFF2E2B28),   // slightly lighter for inputs
    onSurfaceVariant = Color(0xFFCBC5BC),

    outline          = Color(0xFF948F87),
    outlineVariant   = Color(0xFF3A3733),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize   = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = AppTypography,
        content     = content
    )
}
