package com.verisonder.sonderlock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Long press a photo to start selecting, then drag across the grid or tap to add more.
 * Sharing and deleting live here, on the photos themselves, rather than behind a settings
 * screen — that is where anyone would reach for them.
 *
 * Nothing here says whether it is the real vault or the decoy. Someone standing over your
 * shoulder learns nothing from it, which is why there is no badge, banner or hint that a
 * second vault exists anywhere on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    vault: Vault,
    activity: FragmentActivity,
    onAdd: () -> Unit,
    onOpen: (Int) -> Unit,
    onShare: (List<String>) -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    val items = remember(vault, VaultSession.contentsChanged) { vault.items() }
    val selected = remember(items) { mutableListOf<String>().toMutableStateList() }
    val grid = rememberLazyGridState()
    val selecting = selected.isNotEmpty()

    var fingerprintOffer by remember {
        mutableStateOf(
            BiometricKey.isAvailable(activity) && !BiometricKey.isEnabled(activity.filesDir),
        )
    }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    fun toggle(id: String) {
        if (id in selected) selected.remove(id) else selected.add(id)
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onShare(selected.toList()) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                        TextButton(onClick = {
                            if (selected.size == items.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(items.map { it.id })
                            }
                        }) { Text(if (selected.size == items.size) "None" else "All") }
                    },
                    // A different colour is the whole signal that the bar has changed
                    // meaning, so the same icons cannot be pressed by habit.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("SonderLock") },
                    actions = {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = onLock) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (items.isNotEmpty() && !selecting) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (fingerprintOffer && !selecting) {
                OfferCard(
                    title = "Turn on fingerprint unlock?",
                    detail = "Your password still works.",
                    action = "Turn on",
                    onAction = {
                        BiometricPrompts.enable(
                            activity,
                            activity.filesDir,
                            vault.masterKeyCopy(),
                        ) { ok, message ->
                            fingerprintOffer = !ok
                            note = if (ok) "Fingerprint unlock is on." else message
                        }
                    },
                    onDismiss = { fingerprintOffer = false },
                )
            }

            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (items.isEmpty()) {
                // An empty screen is an invitation, not a status report.
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Nothing in here yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Photos and videos you add are encrypted and removed from your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    ExtendedFloatingActionButton(
                        onClick = onAdd,
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Add photos") },
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.dragToSelect(
                        state = grid,
                        onStart = { i -> items.getOrNull(i)?.id?.let { if (it !in selected) selected.add(it) } },
                        onOver = { i -> items.getOrNull(i)?.id?.let { if (it !in selected) selected.add(it) } },
                        onFinish = {},
                    ),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        Tile(
                            vault = vault,
                            item = item,
                            isSelected = item.id in selected,
                            onClick = { if (selecting) toggle(item.id) else onOpen(index) },
                            onLongClick = { toggle(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(if (count == 1) "Delete this item?" else "Delete $count items?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    val doomed = items.filter { it.id in selected }
                    selected.clear()
                    doomed.forEach { runCatching { vault.delete(it) } }
                    VaultSession.noteContentsChanged()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    vault: Vault,
    item: VaultItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail by rememberVaultThumbnail(vault, item)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
        if (item.mimeType.startsWith("video/")) {
            Text(
                "\u25B6",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}

@Composable
private fun OfferCard(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAction) { Text(action) }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
