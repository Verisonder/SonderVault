package com.verisonder.sondervault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.R

/**
 * The Verisonder look, shared with SonderShield: near-black page, panels a step lighter,
 * one loud accent, Unbounded for headings and numbers, DM Sans for everything else.
 *
 * Screens read colours through [Palette]. Material components still work because the
 * Material colour scheme is built from the same colours.
 */
data class VaultColors(
    val bg: Color,
    /** Cards on the page, and the section under the swoosh. */
    val panel: Color,
    /** Cards sitting on a panel. */
    val card2: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    /** Fills: buttons, toggles, picked options. */
    val accent: Color,
    /** The accent used as text on [bg]. */
    val accentText: Color,
    val onAccent: Color,
    /** Picked filter chip. */
    val pill: Color,
    val onPill: Color,
    val warn: Color,
    val danger: Color,
    val off: Color,
    val dark: Boolean,
)

enum class ThemeMode(val id: String, val label: String) {
    Default("default", "Default"),
    Light("light", "Light"),
    System("system", "System"),
    You("you", "Material You");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default
        val youAvailable get() = Build.VERSION.SDK_INT >= 31
    }
}

val Electric = Color(0xFF3B82FF)

/** Default: pure black, panels a step lighter. */
val DefaultColors = VaultColors(
    bg = Color(0xFF000000), panel = Color(0xFF111111), card2 = Color(0xFF1E1E1E), line = Color(0xFF1A1A1A),
    text = Color(0xFFFFFFFF), dim = Color(0xFFBDBDBD), faint = Color(0xFF8A8A8A),
    accent = Electric, accentText = Electric, onAccent = Color(0xFF0D0D0D),
    pill = Color(0xFFFFFFFF), onPill = Color(0xFF0D0D0D), warn = Color(0xFFFFB020),
    danger = Color(0xFFFF4D3D), off = Color(0xFF3A3A3A), dark = true,
)

/** System at night: the soft dark grey instead of pure black. */
val SoftDarkColors = DefaultColors.copy(
    bg = Color(0xFF0D0D0D), panel = Color(0xFF1C1C1C), card2 = Color(0xFF2A2A2A), line = Color(0xFF262626),
)

val LightColors = VaultColors(
    bg = Color(0xFFF5F5F4), panel = Color(0xFFFFFFFF), card2 = Color(0xFFF0F0EE), line = Color(0xFFE2E2E0),
    text = Color(0xFF0D0D0D), dim = Color(0xFF3A3A3A), faint = Color(0xFF6B6B6B),
    accent = Electric, accentText = Color(0xFF1D5BD8), onAccent = Color(0xFF0D0D0D),
    pill = Color(0xFF0D0D0D), onPill = Color(0xFFFFFFFF), warn = Color(0xFFB26A00),
    danger = Color(0xFFD12C1F), off = Color(0xFFC7C7C7), dark = false,
)

private fun youColors(s: ColorScheme, dark: Boolean) = VaultColors(
    bg = s.surface, panel = s.surfaceContainer, card2 = s.surfaceContainerHighest, line = s.outlineVariant,
    text = s.onSurface, dim = s.onSurfaceVariant, faint = s.outline,
    accent = s.primary, accentText = s.primary, onAccent = s.onPrimary,
    pill = s.onSurface, onPill = s.surface, warn = if (dark) Color(0xFFFFB020) else Color(0xFFB26A00),
    danger = s.error, off = s.outlineVariant, dark = dark,
)

val LocalVaultColors = staticCompositionLocalOf { DefaultColors }

/** Colours of the current theme. Read inside composables only. */
object Palette {
    private val c: VaultColors @Composable @ReadOnlyComposable get() = LocalVaultColors.current
    val bg @Composable @ReadOnlyComposable get() = c.bg
    val panel @Composable @ReadOnlyComposable get() = c.panel
    val card2 @Composable @ReadOnlyComposable get() = c.card2
    val line @Composable @ReadOnlyComposable get() = c.line
    val text @Composable @ReadOnlyComposable get() = c.text
    val dim @Composable @ReadOnlyComposable get() = c.dim
    val faint @Composable @ReadOnlyComposable get() = c.faint
    val accent @Composable @ReadOnlyComposable get() = c.accent
    val accentText @Composable @ReadOnlyComposable get() = c.accentText
    val onAccent @Composable @ReadOnlyComposable get() = c.onAccent
    val pill @Composable @ReadOnlyComposable get() = c.pill
    val onPill @Composable @ReadOnlyComposable get() = c.onPill
    val warn @Composable @ReadOnlyComposable get() = c.warn
    val danger @Composable @ReadOnlyComposable get() = c.danger
    val off @Composable @ReadOnlyComposable get() = c.off
    val dark @Composable @ReadOnlyComposable get() = c.dark
}

/** Unbounded: headings and numbers. */
val Display = FontFamily(
    Font(R.font.unbounded_bold, FontWeight.Bold),
    Font(R.font.unbounded_extrabold, FontWeight.ExtraBold),
)

/** DM Sans: everything else. */
val Body = FontFamily(
    Font(R.font.dmsans_regular, FontWeight.Normal),
    Font(R.font.dmsans_medium, FontWeight.Medium),
    Font(R.font.dmsans_bold, FontWeight.Bold),
)

/** Big Unbounded heading, slightly tightened. */
fun display(size: Int, weight: FontWeight = FontWeight.ExtraBold) = TextStyle(
    fontFamily = Display, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.08f).sp,
    letterSpacing = (-0.025f * size).sp,
)

private fun schemeOf(c: VaultColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accent, onPrimaryContainer = c.onAccent,
        secondary = c.accent, onSecondary = c.onAccent,
        secondaryContainer = c.card2, onSecondaryContainer = c.text,
        tertiary = c.accent, onTertiary = c.onAccent,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.panel, onSurfaceVariant = c.dim,
        surfaceContainer = c.panel, surfaceContainerHigh = c.panel, surfaceContainerHighest = c.card2,
        surfaceContainerLow = c.panel, surfaceContainerLowest = c.bg,
        inverseSurface = c.text, inverseOnSurface = c.bg,
        outline = c.faint, outlineVariant = c.line,
        error = c.danger, onError = Color.White,
        errorContainer = c.danger.copy(alpha = 0.18f), onErrorContainer = c.text,
        scrim = Color.Black,
    )
}

private val type = Typography().let { t ->
    fun TextStyle.body() = copy(fontFamily = Body)
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        displayMedium = t.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        displaySmall = t.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.body(), bodyMedium = t.bodyMedium.body(), bodySmall = t.bodySmall.body(),
        labelLarge = t.labelLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.body(), labelSmall = t.labelSmall.body(),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Accent choices in Settings. */
val AccentChoices = listOf(
    Color(0xFF3B82FF), Color(0xFF38B6FF), Color(0xFF5B6CFF), Color(0xFF00D1FF),
    Color(0xFFC8FF2E), Color(0xFF00C46A), Color(0xFFFFD60A), Color(0xFFFF7A1A),
    Color(0xFFFF4D3D), Color(0xFFFF4FA3), Color(0xFFA66BFF), Color(0xFFF2F2F2),
)

/** Black or white, whichever has more contrast on [c]. */
fun onColor(c: Color): Color {
    val l = c.luminance()
    val black = Color(0xFF0D0D0D)
    val withBlack = (l + 0.05f) / (black.luminance() + 0.05f)
    val withWhite = 1.05f / (l + 0.05f)
    return if (withBlack >= withWhite) black else Color.White
}

private fun accentAsText(a: Color, dark: Boolean): Color {
    var c = a
    if (dark) {
        repeat(6) { if (c.luminance() < 0.16f) c = lerp(c, Color.White, 0.18f) }
    } else {
        repeat(8) { if (c.luminance() > 0.18f) c = lerp(c, Color.Black, 0.16f) }
    }
    return c
}

private fun VaultColors.withAccent(a: Color) = copy(accent = a, accentText = accentAsText(a, dark), onAccent = onColor(a))

/**
 * The chosen look, read by the whole app. Loaded from [com.verisonder.sondervault.Preferences]
 * at start and changed from Settings. It says nothing about what is in the vault, so it is
 * fine to keep outside it.
 */
object Look {
    var mode by mutableStateOf(ThemeMode.Default)
    var accent by mutableStateOf(Electric)
}

@Composable
fun colorsFor(mode: ThemeMode, accent: Color): VaultColors = when (mode) {
    ThemeMode.Default -> DefaultColors.withAccent(accent)
    ThemeMode.Light -> LightColors.withAccent(accent)
    ThemeMode.System -> if (isSystemInDarkTheme()) SoftDarkColors.withAccent(accent) else LightColors.withAccent(accent)
    ThemeMode.You -> if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        youColors(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), dark)
    } else DefaultColors.withAccent(accent)
}

@Composable
fun SonderVaultTheme(content: @Composable () -> Unit) {
    val colors = colorsFor(Look.mode, Look.accent)
    CompositionLocalProvider(LocalVaultColors provides colors) {
        MaterialTheme(colorScheme = schemeOf(colors), typography = type, shapes = shapes, content = content)
    }
}
