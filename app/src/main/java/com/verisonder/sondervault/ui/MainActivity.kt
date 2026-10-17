package com.verisonder.sondervault.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.verisonder.sondervault.Preferences
import com.verisonder.sondervault.ui.theme.Look
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.SonderVaultTheme
import com.verisonder.sondervault.ui.theme.ThemeMode
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore

class MainActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = VaultStore(filesDir)
        Look.mode = ThemeMode.of(Preferences.theme(this))
        Preferences.accent(this)?.let { Look.accent = Color(it) }
        setContent {
            SonderVaultTheme {
                // Status and navigation bar icons follow the page: dark on light themes.
                val dark = Palette.dark
                val page = Palette.bg
                SideEffect {
                    window.statusBarColor = page.toArgb()
                    window.navigationBarColor = page.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                AppRoot(store, this)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var started = false
    private val lockIfStillAway = Runnable { if (!started) VaultSession.lock() }

    override fun onStart() {
        super.onStart()
        started = true
        handler.removeCallbacks(lockIfStillAway)
        VaultSession.noteForeground()
    }

    /**
     * Locking here rather than on a timer. The vault is open exactly as long as it is on
     * screen, so a phone taken out of someone's hand is a phone with a locked vault.
     *
     * onStop also fires when the app starts something itself — the photo picker, a share
     * sheet — which is why the session can be told to expect that and skip one lock.
     */
    override fun onStop() {
        super.onStop()
        started = false
        if (VaultSession.expectingExternalActivity) {
            // Kept open for the file picker or a confirmation the app itself started —
            // but only for as long as that should take. Someone who goes from the picker
            // to the home screen instead of back would otherwise leave the vault open
            // in memory indefinitely, because nothing else would ever lock it.
            handler.postDelayed(lockIfStillAway, VaultSession.EXTERNAL_WINDOW_MS)
        } else {
            VaultSession.lock()
        }
    }
}
