package com.verisonder.sonderlock.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore

/**
 * Which screen is showing follows from state rather than from a navigation graph.
 *
 * There are three, they are mutually exclusive, and the transitions are one-way — a
 * navigation library here would be more machinery than the problem has.
 */
@Composable
fun AppRoot(store: VaultStore, activity: FragmentActivity) {
    var configured by remember { mutableStateOf(store.isConfigured) }
    val opened = VaultSession.opened

    Surface(modifier = Modifier.fillMaxSize()) {
        when {
            !configured -> SetupScreen(store) { configured = true }
            opened == null -> UnlockScreen(store, activity) { }
            else -> VaultScreen(opened.vault, activity) { VaultSession.lock() }
        }
    }
}
