package com.verisonder.sonderlock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Warm greys, from darkroom brown to paper white, and no accent colour anywhere.
 *
 * That last part is deliberate. In a vault full of photographs the only colour on screen
 * should be the photographs; anything else competes with the thing the person came to
 * look at. It also keeps the app looking like a utility rather than like somewhere worth
 * prying into, which matters more here than looking striking does.
 */
private val Ink = Color(0xFF1A1614)
private val Raised = Color(0xFF241F1C)
private val Edge = Color(0xFF3A322D)
private val Paper = Color(0xFFE8E2DA)
private val Muted = Color(0xFF8A8079)
private val Alarm = Color(0xFFB4553F)

private val Dark = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = Muted,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    outline = Edge,
    outlineVariant = Edge,
    error = Alarm,
    onError = Paper,
)

// Light exists because a phone in daylight is a real place this gets used, not because
// every app has two themes.
private val Light = lightColorScheme(
    primary = Color(0xFF2A2320),
    onPrimary = Color(0xFFFAF7F2),
    secondary = Color(0xFF6B615A),
    onSecondary = Color(0xFFFAF7F2),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF2A2320),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF2A2320),
    surfaceVariant = Color(0xFFEDE7DF),
    onSurfaceVariant = Color(0xFF6B615A),
    outline = Color(0xFFD4CCC2),
    error = Color(0xFF8F3D2B),
    onError = Color(0xFFFAF7F2),
)

private val SonderTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun SonderLockTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = SonderTypography,
        content = content,
    )
}
