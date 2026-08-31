package com.verisonder.sonderlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.security.BiometricKey
import com.verisonder.sonderlock.security.BiometricPrompts
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem

/**
 * What you see once the vault is open.
 *
 * Nothing on this screen says whether it is the real vault or the decoy. Someone standing
 * over your shoulder learns nothing from it, and neither does a screenshot, which is why
 * there is no badge, banner or hint of a second vault anywhere.
 */
@Composable
fun VaultScreen(
    vault: Vault,
    activity: FragmentActivity,
    onLock: () -> Unit,
) {
    val items = remember(vault) { vault.items() }
    var fingerprintOffer by remember {
        mutableStateOf(
            BiometricKey.isAvailable(activity) && !BiometricKey.isEnabled(activity.filesDir),
        )
    }
    var note by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (items.isEmpty()) "Empty" else "${items.size} items",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onLock) { Text("Lock") }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(24.dp))

        if (fingerprintOffer) {
            Text("Turn on fingerprint unlock?", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Your password still works. Adding a new fingerprint to this phone turns " +
                    "it back off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    BiometricPrompts.enable(
                        activity,
                        activity.filesDir,
                        vault.masterKeyForBiometrics(),
                    ) { ok, message ->
                        fingerprintOffer = !ok
                        note = if (ok) "Fingerprint unlock is on." else message
                    }
                }) { Text("Turn on") }
                TextButton(onClick = { fingerprintOffer = false }) { Text("Not now") }
            }
            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))
        }

        if (items.isEmpty()) {
            // An empty screen is an invitation, not a status report.
            Text("Add a photo to get started.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Importing is not wired up yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ItemList(items)
        }
    }
}

@Composable
private fun ItemList(items: List<VaultItem>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { it.id }) { item ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${item.size / 1024} KB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
