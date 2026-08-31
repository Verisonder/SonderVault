package com.verisonder.sonderlock.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.verisonder.sonderlock.media.DeviceMedia
import com.verisonder.sonderlock.media.DeviceMediaItem
import com.verisonder.sonderlock.media.MediaAccess
import com.verisonder.sonderlock.media.MediaImporter
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choosing what to bring in.
 *
 * This is the app's own grid rather than the system photo picker, and that is forced
 * rather than chosen: the picker returns uris that cannot be deleted afterwards, so using
 * it would mean the original always stays in the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(vault: Vault, onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as android.app.Activity
    // Keyed on the foreground counter so returning from the app's settings page is
    // noticed; nothing tells the app that a permission changed out there.
    var hasAccess by remember(VaultSession.foregroundCount) {
        mutableStateOf(MediaAccess.hasReadAccess(context))
    }
    var asked by remember { mutableStateOf(false) }
    // Re-read on every return to the foreground: the media management toggle lives in
    // Settings and nothing tells the app when it changes.
    val needsManagement = remember(VaultSession.foregroundCount) {
        MediaAccess.mediaManagementPossible() && !MediaAccess.canManageMedia(context)
    }
    var media by remember { mutableStateOf<List<DeviceMediaItem>?>(null) }
    val selected = remember { mutableListOf<Long>().toMutableStateList() }
    var working by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val askForAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        asked = true
        hasAccess = MediaAccess.hasReadAccess(context)
    }

    val confirmDelete = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { _: androidx.activity.result.ActivityResult ->
        working = null
        onDone()
    }

    LaunchedEffect(hasAccess, needsManagement) {
        if (!hasAccess) {
            // Only once. Asking again after a refusal shows nothing at all, and a button
            // that silently does nothing is worse than one that explains itself.
            if (!asked) askForAccess.launch(MediaAccess.readPermissions())
        } else if (!needsManagement && media == null) {
            media = withContext(Dispatchers.IO) { DeviceMedia.query(context) }
        }
    }

    fun importSelected() {
        val chosen = media.orEmpty().filter { it.id in selected }
        if (chosen.isEmpty()) return
        working = "Encrypting ${chosen.size}"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                MediaImporter.importAll(context, vault, chosen)
            }
            val sender = MediaImporter.deleteRequest(context, outcome.importedUris)
            if (sender == null) {
                working = null
                onDone()
            } else {
                // The confirmation is another activity, so the vault must not lock behind it.
                VaultSession.expectingExternalActivity = true
                working = "Removing originals"
                confirmDelete.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selected.isEmpty()) "Add to vault" else "${selected.size} selected")
                },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = working == null) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    val all = media.orEmpty()
                    if (all.isNotEmpty() && working == null) {
                        TextButton(onClick = {
                            if (selected.size == all.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(all.map { it.id })
                            }
                        }) {
                            Text(if (selected.size == all.size) "None" else "All")
                        }
                    }
                },
            )
        },
        bottomBar = {
            val ready = working == null && hasAccess && !needsManagement && !media.isNullOrEmpty()
            if (ready) {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            onClick = { importSelected() },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (selected.isEmpty()) "Import" else "Import ${selected.size}")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Originals are deleted from this phone. Cloud backups are not.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                working != null -> Centred {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(working!!, style = MaterialTheme.typography.bodyMedium)
                }

                !hasAccess -> {
                    val partial = MediaAccess.hasPartialAccess(context)
                    val canAsk = !asked || MediaAccess.canAskAgain(activity)
                    Centred {
                        Text(
                            if (partial) "SonderLock needs all your photos"
                            else "Needed to import your photos",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (partial) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Selecting a few is not enough — it has to show your " +
                                    "whole library and remove the originals afterwards.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        if (canAsk && !partial) {
                            Button(onClick = {
                                askForAccess.launch(MediaAccess.readPermissions())
                            }) { Text("Allow access") }
                        } else {
                            // Past a second refusal, or on a partial grant, the system
                            // dialog will not appear again. Settings is the only route.
                            Button(onClick = {
                                VaultSession.expectingExternalActivity = true
                                context.startActivity(MediaAccess.appSettings(context))
                            }) { Text("Open app settings") }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Permissions, then Photos and videos, then Allow all.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Importing without this leaves the original in the gallery behind a
                // confirmation the user can decline, which would make hiding a photo
                // optional.
                needsManagement -> Centred {
                    Text("Turn on media management", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SonderLock needs it to remove originals from your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        MediaAccess.mediaManagementSettings(context)?.let {
                            VaultSession.expectingExternalActivity = true
                            context.startActivity(it)
                        }
                    }) { Text("Open Settings") }
                }

                media == null -> Centred { CircularProgressIndicator() }

                media!!.isEmpty() -> Centred {
                    Text("No photos or videos on this phone",
                        style = MaterialTheme.typography.titleMedium)
                }

                else -> {
                    val all = media!!
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.dragToSelect(
                            state = grid,
                            onStart = { index ->
                                all.getOrNull(index)?.id?.let { if (it !in selected) selected.add(it) }
                            },
                            onOver = { index ->
                                all.getOrNull(index)?.id?.let { if (it !in selected) selected.add(it) }
                            },
                            onFinish = {},
                        ),
                    ) {
                        itemsIndexed(all, key = { _, item -> item.id }) { _, item ->
                            val isSelected = item.id in selected
                            PickerTile(item, isSelected) {
                                if (isSelected) selected.remove(item.id) else selected.add(item.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerTile(item: DeviceMediaItem, isSelected: Boolean, onToggle: () -> Unit) {
    val thumbnail by rememberDeviceThumbnail(item)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        thumbnail?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isSelected) {
            // A scrim plus a filled check, rather than fading the photo out. Half-visible
            // thumbnails made it hard to tell what had been picked.
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
        if (item.isVideo) {
            Text(
                "\u25B6",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}
