package com.verisonder.sondervault.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.display
import com.verisonder.sondervault.media.DeviceMedia
import com.verisonder.sondervault.media.DeviceMediaItem
import com.verisonder.sondervault.media.MediaAccess
import com.verisonder.sondervault.media.MediaImporter
import com.verisonder.sondervault.media.PickKind
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultSession
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
fun PickerScreen(vault: Vault, kind: PickKind, onDone: () -> Unit) {
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
        VaultSession.externalActivityFinished()
        working = null
        onDone()
    }

    LaunchedEffect(hasAccess, needsManagement) {
        if (!hasAccess) {
            // Only once. Asking again after a refusal shows nothing at all, and a button
            // that silently does nothing is worse than one that explains itself.
            if (!asked) askForAccess.launch(MediaAccess.readPermissions())
        } else if (!needsManagement && media == null) {
            media = withContext(Dispatchers.IO) { DeviceMedia.query(context, kind) }
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

    val ready = working == null && hasAccess && !needsManagement && !media.isNullOrEmpty()

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        TopBar(onBack = onDone, close = true, enabled = working == null) {
            val all = media.orEmpty()
            if (all.isNotEmpty() && working == null) {
                Chip(if (selected.size == all.size) "None" else "All", selected.size == all.size) {
                    if (selected.size == all.size) {
                        selected.clear()
                    } else {
                        selected.clear()
                        selected.addAll(all.map { it.id })
                    }
                }
            }
        }
        ScreenHeader(
            when {
                selected.isNotEmpty() -> "${selected.size} picked."
                kind == PickKind.VIDEOS -> "Add videos."
                else -> "Add photos."
            },
            kicker = "From your gallery",
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                working != null -> Centred {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(16.dp))
                    Text(working!!, color = Palette.dim, fontSize = 14.sp)
                }

                !hasAccess -> {
                    val partial = MediaAccess.hasPartialAccess(context)
                    val canAsk = !asked || MediaAccess.canAskAgain(activity)
                    Centred {
                        Text(
                            if (partial) "SonderVault needs all your photos."
                            else "Needed to import your photos.",
                            style = display(18),
                            color = Palette.text,
                            textAlign = TextAlign.Center,
                        )
                        if (partial) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Selecting a few is not enough — it has to show your " +
                                    "whole library and remove the originals afterwards.",
                                color = Palette.dim,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        if (canAsk && !partial) {
                            PillButton("Allow access") {
                                askForAccess.launch(MediaAccess.readPermissions())
                            }
                        } else {
                            // Past a second refusal, or on a partial grant, the system
                            // dialog will not appear again. Settings is the only route.
                            PillButton("Open app settings") {
                                VaultSession.expectingExternalActivity = true
                                context.startActivity(MediaAccess.appSettings(context))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Permissions, then Photos and videos, then Allow all.",
                                color = Palette.dim,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // Importing without this leaves the original in the gallery behind a
                // confirmation the user can decline, which would make hiding a photo
                // optional.
                needsManagement -> Centred {
                    Text("Turn on media management.", style = display(18), color = Palette.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SonderVault needs it to remove originals from your phone.",
                        color = Palette.dim,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    PillButton("Open Settings") {
                        MediaAccess.mediaManagementSettings(context)?.let {
                            VaultSession.expectingExternalActivity = true
                            context.startActivity(it)
                        }
                    }
                }

                media == null -> Centred { CircularProgressIndicator(color = Palette.accent) }

                media!!.isEmpty() -> Centred {
                    Text(
                        if (kind == PickKind.VIDEOS) "No videos on this phone."
                        else "No photos on this phone.",
                        style = display(18),
                        color = Palette.text,
                    )
                }

                else -> {
                    val all = media!!
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize().dragToSelect(
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

        if (ready) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Palette.panel)
                    .navigationBarsPadding().padding(horizontal = Gutter, vertical = 14.dp),
            ) {
                PillButton(
                    if (selected.isEmpty()) "Import" else "Import ${selected.size}",
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { importSelected() }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Originals are deleted from this phone.",
                    color = Palette.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun PickerTile(item: DeviceMediaItem, isSelected: Boolean, onToggle: () -> Unit) {
    val thumbnail by rememberDeviceThumbnail(item)
    // Same pick look as the vault grid: the photo shrinks inside an accent frame.
    val inset by animateDpAsState(if (isSelected) 8.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "pick")
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Palette.accent else Palette.panel)
            .clickable(onClick = onToggle),
    ) {
        Box(Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (isSelected) 10.dp else 14.dp)).background(Palette.panel)) {
            thumbnail?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.isVideo) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).size(24.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { Text("\u25B6", color = Color.White, fontSize = 10.sp) }
            }
        }
        if (isSelected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Palette.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(LineIcons.check, null, tint = Palette.onAccent, modifier = Modifier.size(15.dp)) }
        }
    }
}
