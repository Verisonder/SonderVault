package com.verisonder.sonderlock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.verisonder.sonderlock.CrashLog
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore

/**
 * Which screen is showing follows from state rather than from a navigation graph.
 *
 * They are mutually exclusive and the transitions are shallow, so a navigation library
 * here would be more machinery than the problem has.
 */
@Composable
fun AppRoot(store: VaultStore, activity: FragmentActivity) {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var configured by remember { mutableStateOf(store.isConfigured) }
    var picking by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val opened = VaultSession.opened

    // Locking while the picker is up would strand it against a closed vault, so the flag
    // is dropped the moment the vault is not open.
    if (opened == null) {
        if (picking) picking = false
        if (viewing != null) viewing = null
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val report = crash
        if (report != null) {
            CrashReport(report, onDismiss = { CrashLog.clear(context); crash = null })
            return@Surface
        }
        when {
            !configured -> SetupScreen(store) { configured = true }
            opened == null -> UnlockScreen(store, activity) { }
            picking -> PickerScreen(opened.vault) {
                VaultSession.noteContentsChanged()
                picking = false
            }
            viewing != null -> ViewerScreen(
                vault = opened.vault,
                // Read here rather than passed down, so the list the pager walks is the
                // one on disk right now: putting an item back removes it mid-view.
                items = remember(VaultSession.contentsChanged) { opened.vault.items() },
                startIndex = viewing ?: 0,
                onClose = { viewing = null },
            )
            else -> VaultScreen(
                vault = opened.vault,
                activity = activity,
                onAdd = { picking = true },
                onOpen = { viewing = it },
                onLock = { VaultSession.lock() },
            )
        }
    }
}

/**
 * Shown once after a crash, then deleted. Nothing else in the app looks like this on
 * purpose: it should be obvious that something went wrong rather than look like a screen.
 */
@Composable
private fun CrashReport(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("SonderLock closed unexpectedly", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy report") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
