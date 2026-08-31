package com.verisonder.sonderlock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material You, with as little on top of it as possible.
 *
 * On Android 12 and above the palette is derived from the user's wallpaper, so the app
 * looks like it belongs on their phone rather than like a thing that arrived from
 * somewhere else. Below that, the Material baseline scheme.
 *
 * No custom typography and no custom shapes. The M3 defaults are a complete, tested type
 * scale and shape system, and replacing them piecemeal — which is what the previous
 * version did — produces something that is neither Material nor deliberately anything
 * else. Everything in the app addresses colour by role (surface, primary, onSurfaceVariant)
 * rather than by hex, which is what lets dynamic colour work at all.
 */
@Composable
fun SonderLockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
