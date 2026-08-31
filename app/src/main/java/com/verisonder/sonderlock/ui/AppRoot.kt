package com.verisonder.sonderlock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.CrashLog
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore

/**
 * Which screen is showing follows from state rather than from a navigation graph.
 *
 * They are mutually exclusive and the transitions are shallow, so a navigation library
 * here would be more machinery than the problem has.
 */
private sealed interface Where {
    data object Grid : Where
    data object Picking : Where
    data class Viewing(val index: Int) : Where
    data object Settings : Where
    data object Duress : Where
    data class DecoyPhotos(val decoy: com.verisonder.sonderlock.vault.Vault) : Where
    data class Sharing(val itemIds: List<String>, val isBackup: Boolean) : Where
    data object Restoring : Where
}

@Composable
fun AppRoot(store: VaultStore, activity: FragmentActivity) {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var configured by remember { mutableStateOf(store.isConfigured) }
    var where by remember { mutableStateOf<Where>(Where.Grid) }
    val opened = VaultSession.opened

    // Everything below the vault screen depends on the vault being open, so locking drops
    // straight back rather than leaving a screen stranded against a closed vault.
    if (opened == null && where != Where.Grid) where = Where.Grid

    Surface(modifier = Modifier.fillMaxSize()) {
        val report = crash
        if (report != null) {
            CrashReport(report, onDismiss = { CrashLog.clear(context); crash = null })
            return@Surface
        }
        if (!configured) {
            SetupScreen(store) { configured = true }
            return@Surface
        }
        if (opened == null) {
            UnlockScreen(store, activity) { }
            return@Surface
        }

        val vault = opened.vault

        // Without this the system back button walks straight past the app's own
        // navigation and closes it, which from inside a photo looks like the app
        // crashing. Every screen below the grid goes back to where it was opened from.
        BackHandler(enabled = where != Where.Grid) {
            where = when (where) {
                is Where.Duress, Where.Restoring -> Where.Settings
                is Where.DecoyPhotos -> Where.Settings
                is Where.Sharing -> if ((where as Where.Sharing).isBackup) Where.Settings else Where.Grid
                else -> Where.Grid
            }
        }

        when (val here = where) {
            Where.Grid -> VaultScreen(
                store = store,
                vault = vault,
                activity = activity,
                onAdd = { where = Where.Picking },
                onOpen = { where = Where.Viewing(it) },
                onShare = { where = Where.Sharing(it, isBackup = false) },
                onSettings = { where = Where.Settings },
                onLock = { VaultSession.lock() },
            )

            Where.Picking -> PickerScreen(vault) {
                VaultSession.noteContentsChanged()
                where = Where.Grid
            }

            is Where.Viewing -> ViewerScreen(
                store = store,
                vault = vault,
                activity = activity,
                // Read here rather than passed down, so the list the pager walks is the
                // one on disk right now: putting an item back removes it mid-view.
                items = remember(VaultSession.contentsChanged) { vault.items() },
                startIndex = here.index,
                onShare = { where = Where.Sharing(it, isBackup = false) },
                onClose = { where = Where.Grid },
            )

            Where.Settings -> SettingsScreen(
                store = store,
                vault = vault,
                activity = activity,
                onDuress = { where = Where.Duress },
                onBackUp = {
                    where = Where.Sharing(vault.items().map { it.id }, isBackup = true)
                },
                onRestore = { where = Where.Restoring },
                onClose = { where = Where.Grid },
            )

            Where.Duress -> DuressScreen(
                store = store,
                vault = vault,
                onChooseDecoyPhotos = { where = Where.DecoyPhotos(it) },
                onClose = { where = Where.Settings },
            )

            // The picker again, pointed at the second vault. A decoy is an ordinary vault
            // and filling one is an ordinary import.
            is Where.DecoyPhotos -> PickerScreen(here.decoy) { where = Where.Settings }

            is Where.Sharing -> ShareScreen(
                store = store,
                vault = vault,
                activity = activity,
                itemIds = here.itemIds,
                isBackup = here.isBackup,
                onDone = { where = if (here.isBackup) Where.Settings else Where.Grid },
            )

            Where.Restoring -> RestoreScreen(vault) { where = Where.Settings }
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
        Text("SonderLock closed unexpectedly", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy report") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
