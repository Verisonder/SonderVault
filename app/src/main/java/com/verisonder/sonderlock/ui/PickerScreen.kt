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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
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
@Composable
fun PickerScreen(vault: Vault, onDone: () -> Unit) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(MediaAccess.hasReadAccess(context)) }
    // Re-read on every return to the foreground: the media management toggle lives in
    // Settings and nothing tells the app when it changes.
    val needsManagement = remember(VaultSession.foregroundCount) {
        MediaAccess.mediaManagementPossible() && !MediaAccess.canManageMedia(context)
    }
    var media by remember { mutableStateOf<List<DeviceMediaItem>?>(null) }
    val selected = remember { mutableListOf<Long>().toMutableStateList() }
    var working by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val askForAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasAccess = MediaAccess.hasReadAccess(context) }

    val confirmDelete = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        report = if (result.resultCode == Activity.RESULT_OK) {
            "Originals removed from the phone."
        } else {
            "Imported. The originals are still in your gallery."
        }
        working = null
        onDone()
    }

    LaunchedEffect(hasAccess, needsManagement) {
        if (!hasAccess) {
            askForAccess.launch(MediaAccess.readPermissions())
        } else if (!needsManagement && media == null) {
            media = withContext(Dispatchers.IO) { DeviceMedia.query(context) }
        }
    }

    fun importSelected() {
        val chosen = media.orEmpty().filter { it.id in selected }
        if (chosen.isEmpty()) return
        working = "Encrypting ${chosen.size}…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                MediaImporter.importAll(context, vault, chosen)
            }
            if (outcome.failures.isNotEmpty()) {
                report = "${outcome.failures.size} could not be read and were left alone."
            }
            val sender = MediaImporter.deleteRequest(context, outcome.importedUris)
            if (sender == null) {
                working = null
                onDone()
            } else {
                // The confirmation is another activity, so the vault must not lock behind it.
                VaultSession.expectingExternalActivity = true
                working = "Removing originals…"
                confirmDelete.launch(IntentSenderRequest.Builder(sender).build())
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone, enabled = working == null) { Text("Cancel") }
            Text(
                if (selected.isEmpty()) "Choose photos" else "${selected.size} selected",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Spacer(Modifier.height(12.dp))

        when {
            working != null -> Centred {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(16.dp))
                Text(working!!, style = MaterialTheme.typography.bodyMedium)
            }

            !hasAccess -> Centred {
                Text("Needed to import your photos.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { askForAccess.launch(MediaAccess.readPermissions()) }) {
                    Text("Allow access")
                }
            }

            // Importing without this leaves the original in the gallery behind a
            // confirmation the user can decline, which would make hiding a photo optional.
            needsManagement -> Centred {
                Text("Turn on media management.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "SonderLock needs it to remove originals from your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    MediaAccess.mediaManagementSettings(context)?.let {
                        VaultSession.expectingExternalActivity = true
                        context.startActivity(it)
                    }
                }) { Text("Open Settings") }
            }

            media == null -> Centred { CircularProgressIndicator(strokeWidth = 2.dp) }

            media!!.isEmpty() -> Centred {
                Text("No photos or videos on this phone.", style = MaterialTheme.typography.bodyLarge)
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(media!!, key = { it.id }) { item ->
                        val isSelected = item.id in selected
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (isSelected) selected.remove(item.id) else selected.add(item.id)
                                },
                        ) {
                            val thumbnail by rememberDeviceThumbnail(item)
                            thumbnail?.let {
                                androidx.compose.foundation.Image(
                                    bitmap = it,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                        .alpha(if (isSelected) 0.45f else 1f),
                                )
                            }
                            if (isSelected) {
                                Text(
                                    "✓",
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (item.isVideo) {
                                Text(
                                    "▶",
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                report?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
