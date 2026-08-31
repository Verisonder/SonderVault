package com.verisonder.sonderlock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.security.BiometricKey
import com.verisonder.sonderlock.security.BiometricPrompts
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
import com.verisonder.sonderlock.media.VaultExport
import com.verisonder.sonderlock.media.DocumentImport
import com.verisonder.sonderlock.media.PickKind
import com.verisonder.sonderlock.vault.ItemKind
import com.verisonder.sonderlock.vault.VaultFilter
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore
import com.verisonder.sonderlock.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    store: VaultStore,
    vault: Vault,
    activity: FragmentActivity,
    onImportMedia: (PickKind) -> Unit,
    onOpenShared: () -> Unit,
    onOpen: (Int) -> Unit,
    onShare: (List<String>) -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val all = remember(vault, VaultSession.contentsChanged) { vault.items() }
    var filter by remember { mutableStateOf(VaultFilter.ALL) }
    val items = remember(all, filter) { all.filter { filter.accepts(it) } }
    val selected = remember(all) { mutableListOf<String>().toMutableStateList() }
    var addOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val pickDocuments = androidx.activity.compose.rememberLauncherForActivityResult(
        DocumentImport.PickWritable(),
    ) { uris ->
        VaultSession.externalActivityFinished()
        if (uris.isNotEmpty()) {
            busy = "Encrypting ${uris.size}"
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    DocumentImport.importAll(context, vault, uris)
                }
                busy = null
                note = when {
                    outcome.failed > 0 -> "${outcome.failed} could not be read."
                    outcome.notRemoved > 0 ->
                        "Added. ${outcome.notRemoved} could not be deleted from where they were."
                    else -> "Added ${outcome.imported}."
                }
                VaultSession.noteContentsChanged()
            }
        }
    }
    val grid = rememberLazyGridState()
    val selecting = selected.isNotEmpty()

    var fingerprintOffer by remember {
        mutableStateOf(
            !vault.isDecoy &&
                BiometricKey.isAvailable(activity) &&
                !BiometricKey.isEnabled(activity.filesDir),
        )
    }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingPutBack by remember { mutableStateOf(false) }

    // Back drops the selection first. Leaving the vault while thirty photos are ticked
    // is never what the gesture meant.
    BackHandler(enabled = selecting) { selected.clear() }

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
                        IconButton(onClick = { confirmingPutBack = true }) {
                            Icon(
                                painterResource(R.drawable.ic_put_back),
                                contentDescription = "Put back on phone",
                            )
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
                    title = { Text(if (filter == VaultFilter.ALL) "SonderLock" else filter.label) },
                    actions = {
                        Box {
                            IconButton(onClick = { filterOpen = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_filter),
                                    contentDescription = "Filter",
                                )
                            }
                            DropdownMenu(
                                expanded = filterOpen,
                                onDismissRequest = { filterOpen = false },
                            ) {
                                VaultFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = { filter = option; filterOpen = false },
                                        trailingIcon = {
                                            if (option == filter) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            }
                                        },
                                    )
                                }
                            }
                        }
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
            if (all.isNotEmpty() && !selecting) {
                FloatingActionButton(onClick = { addOpen = true }) {
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
                        BiometricPrompts.enable(activity, activity.filesDir, vault) { ok, message ->
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
                    Text(
                        if (all.isEmpty()) "Nothing in here yet" else "No ${filter.label.lowercase()}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (all.isEmpty()) {
                            "Anything you add is encrypted and removed from your phone."
                        } else {
                            "There is nothing here under this filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    ExtendedFloatingActionButton(
                        onClick = { addOpen = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Add") },
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

    if (addOpen) {
        ModalBottomSheet(onDismissRequest = { addOpen = false }, sheetState = sheetState) {
            AddOption("Photos", "From your gallery") {
                addOpen = false
                onImportMedia(PickKind.IMAGES)
            }
            AddOption("Videos", "From your gallery") {
                addOpen = false
                onImportMedia(PickKind.VIDEOS)
            }
            AddOption("Files", "Documents, PDFs, anything else") {
                addOpen = false
                VaultSession.expectingExternalActivity = true
                pickDocuments.launch(arrayOf("*/*"))
            }
            AddOption("A shared file", "Something sent to you, or a backup") {
                addOpen = false
                onOpenShared()
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingPutBack) {
        val count = selected.size
        ConfirmPassword(
            store = store,
            vault = vault,
            activity = activity,
            reason = if (count == 1) {
                "This writes the file back to your gallery, where anything on the phone " +
                    "can read it."
            } else {
                "This writes $count files back to your gallery, where anything on the " +
                    "phone can read them."
            },
            confirmLabel = "Put back",
            onConfirmed = {
                confirmingPutBack = false
                val chosen = items.filter { it.id in selected }
                selected.clear()
                busy = "Writing"
                scope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        chosen.count { VaultExport.putBackOnPhone(context, vault, it).error != null }
                    }
                    busy = null
                    note = when {
                        failed == 0 && chosen.size == 1 -> "Back on your phone."
                        failed == 0 -> "${chosen.size} back on your phone."
                        else -> "$failed could not be written and are still here."
                    }
                    VaultSession.noteContentsChanged()
                }
            },
            onCancel = { confirmingPutBack = false },
        )
    }

    if (busy != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(busy!!) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
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
    val kind = ItemKind.of(item.mimeType)
    val thumbnail by rememberVaultThumbnail(vault, item)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (kind == ItemKind.FILE) {
            // A document has no picture to show, so it shows what it is instead. A grey
            // square with nothing on it is indistinguishable from one that failed.
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painterResource(R.drawable.ic_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
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
        if (kind == ItemKind.VIDEO) {
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

@Composable
private fun AddOption(title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
