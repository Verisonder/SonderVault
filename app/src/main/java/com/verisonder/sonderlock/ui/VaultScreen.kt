package com.verisonder.sonderlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.security.BiometricKey
import com.verisonder.sonderlock.security.BiometricPrompts
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
import com.verisonder.sonderlock.vault.VaultSession

/**
 * What you see once the vault is open.
 *
 * Nothing here says whether it is the real vault or the decoy. Someone standing over your
 * shoulder learns nothing from it, which is why there is no badge, banner or hint that a
 * second vault exists anywhere on this screen.
 */
@Composable
fun VaultScreen(
    vault: Vault,
    activity: FragmentActivity,
    onAdd: () -> Unit,
    onLock: () -> Unit,
) {
    val items = remember(vault, VaultSession.contentsChanged) { vault.items() }

    var fingerprintOffer by remember {
        mutableStateOf(
            BiometricKey.isAvailable(activity) && !BiometricKey.isEnabled(activity.filesDir),
        )
    }
    var note by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
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
            Row {
                TextButton(onClick = onAdd) { Text("Add") }
                TextButton(onClick = onLock) { Text("Lock") }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(20.dp))

        if (fingerprintOffer) {
            Offer(
                title = "Turn on fingerprint unlock?",
                detail = "Your password still works.",
                action = "Turn on",
                onAction = {
                    BiometricPrompts.enable(
                        activity,
                        activity.filesDir,
                        vault.masterKeyForBiometrics(),
                    ) { ok, message ->
                        fingerprintOffer = !ok
                        note = if (ok) "Fingerprint unlock is on." else message
                    }
                },
                onDismiss = { fingerprintOffer = false },
            )
        }

        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }

        if (items.isEmpty()) {
            // An empty screen is an invitation, not a status report.
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing in here yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAdd) { Text("Add photos") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.id }) { item -> Tile(vault, item) }
            }
        }
    }
}

@Composable
private fun Tile(vault: Vault, item: VaultItem) {
    val thumbnail by rememberVaultThumbnail(vault, item)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.mimeType.startsWith("video/")) {
            Text(
                "\u25B6",
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Offer(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAction) { Text(action) }
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(20.dp))
    }
}
