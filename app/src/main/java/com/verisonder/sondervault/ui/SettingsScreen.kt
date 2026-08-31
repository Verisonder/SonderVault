package com.verisonder.sondervault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.security.BiometricKey
import com.verisonder.sondervault.security.BiometricPrompts
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore

/**
 * Everything that is not looking at photos.
 *
 * The duress row is worded so that it reads as a feature rather than as a confession.
 * Nothing on this screen states whether a duress password is currently set, because
 * someone made to open the vault could be made to open settings too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: VaultStore,
    vault: Vault,
    activity: FragmentActivity,
    onDuress: () -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val items = remember(vault, VaultSession.contentsChanged) { vault.items() }
    val used = remember(VaultSession.contentsChanged) { store.totalSizeOnDisk() }
    // Absent inside the decoy for the same reason as the duress row: turning it on there
    // would wrap the decoy's key, and every later unlock would land in the decoy while
    // claiming to be the real vault. That is how a vault got lost.
    val fingerprintPossible = remember { !vault.isDecoy && BiometricKey.isAvailable(activity) }
    var fingerprintOn by remember(VaultSession.foregroundCount) {
        mutableStateOf(BiometricKey.isEnabled(activity.filesDir))
    }
    var note by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("Vault")
            // Absent inside the decoy, and not merely disabled. Someone who forced this
            // vault open can scroll settings too, and a greyed-out row named "Duress
            // password" tells them exactly what they are looking at and that there is
            // more to find.
            if (!vault.isDecoy) {
                Row(
                    title = "Duress password",
                    detail = "Opens a different set of photos.",
                    onClick = onDuress,
                )
            }
            if (fingerprintPossible || BiometricKey.isEnabled(activity.filesDir)) SwitchRow(
                title = "Fingerprint unlock",
                detail = when {
                    !fingerprintPossible -> "No fingerprint is set up on this phone."
                    note != null -> note!!
                    fingerprintOn -> "Also confirms when files leave the vault."
                    else -> "Your password always works either way."
                },
                checked = fingerprintOn,
                enabled = fingerprintPossible,
                onChange = { wanted ->
                    note = null
                    if (!wanted) {
                        BiometricKey.disable(activity.filesDir)
                        fingerprintOn = false
                    } else {
                        BiometricPrompts.enable(activity, activity.filesDir, vault) { ok, message ->
                            fingerprintOn = ok
                            if (!ok) note = message
                        }
                    }
                },
            )

            HorizontalDivider()
            SectionLabel("Files")
            Row(
                title = "Back up everything",
                detail = "One encrypted file with its own code.",
                onClick = onBackUp,
            )
            Row(
                title = "Open a shared file",
                detail = "Add items from a file someone sent you, or from a backup.",
                onClick = onRestore,
            )

            HorizontalDivider()
            SectionLabel("This vault")
            Row(
                title = "${items.size} items",
                detail = "${used / 1024 / 1024} MB on this phone.",
                onClick = null,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Uninstalling SonderVault deletes everything in it. A backup is the only " +
                    "way to get it back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Row(title: String, detail: String, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onChange(!checked) } else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
