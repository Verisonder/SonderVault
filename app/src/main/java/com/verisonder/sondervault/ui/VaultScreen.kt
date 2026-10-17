package com.verisonder.sondervault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.Preferences
import com.verisonder.sondervault.media.DocumentImport
import com.verisonder.sondervault.media.PickKind
import com.verisonder.sondervault.media.VaultExport
import com.verisonder.sondervault.security.BiometricKey
import com.verisonder.sondervault.security.BiometricPrompts
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.display
import com.verisonder.sondervault.vault.ItemKind
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultFilter
import com.verisonder.sondervault.vault.VaultItem
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore
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
                !BiometricKey.isEnabled(activity.filesDir) &&
                // Turning fingerprint off and then being asked to turn it on again every
                // single time is the app arguing with a decision already made.
                !Preferences.fingerprintOfferDeclined(context),
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

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The bar on top changes meaning when something is picked, and changes colour
            // with it, so the same buttons cannot be pressed by habit.
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    allPicked = selected.size == items.size,
                    onCancel = { selected.clear() },
                    onAll = {
                        if (selected.size == items.size) {
                            selected.clear()
                        } else {
                            selected.clear()
                            selected.addAll(items.map { it.id })
                        }
                    },
                    onShare = { onShare(selected.toList()) },
                    onPutBack = { confirmingPutBack = true },
                    onDelete = { confirmingDelete = true },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = 14.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { Wordmark(20) }
                    IconPill(LineIcons.settings, "Settings", onClick = onSettings)
                    Spacer(Modifier.width(8.dp))
                    IconPill(LineIcons.lock, "Lock", onClick = onLock)
                }
            }

            ScreenHeader(
                title = if (filter == VaultFilter.ALL) "Your vault." else "${filter.label}.",
                kicker = if (all.size == 1) "1 item, encrypted" else "${all.size} items, encrypted",
            )

            if (all.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = Gutter, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VaultFilter.entries.forEach { option ->
                        Chip(option.label, option == filter) { filter = option }
                    }
                }
            }

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
                    onNever = {
                        Preferences.declineFingerprintOffer(context)
                        fingerprintOffer = false
                    },
                )
            }

            note?.let {
                Text(
                    it,
                    color = Palette.dim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            Swoosh(blade = Palette.accent, below = Palette.panel)

            Box(Modifier.fillMaxSize().background(Palette.panel)) {
                if (items.isEmpty()) {
                    // An empty screen is an invitation, not a status report.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).navigationBarsPadding(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(64.dp).enter(0).clip(CircleShape).background(Palette.card2),
                            contentAlignment = Alignment.Center,
                        ) { Icon(LineIcons.inbox, null, tint = Palette.accentText, modifier = Modifier.size(28.dp)) }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            if (all.isEmpty()) "Nothing in here yet." else "No ${filter.label.lowercase()}.",
                            style = display(20),
                            color = Palette.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.enter(1),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (all.isEmpty()) {
                                "Anything you add is encrypted and removed from your phone."
                            } else {
                                "There is nothing here under this filter."
                            },
                            color = Palette.dim,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.enter(2),
                        )
                        Spacer(Modifier.height(24.dp))
                        PillButton("Add", modifier = Modifier.enter(3)) { addOpen = true }
                    }
                } else {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize().dragToSelect(
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

        if (all.isNotEmpty() && !selecting) {
            AddButton(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = Gutter, bottom = 22.dp),
            ) { addOpen = true }
        }
    }

    if (addOpen) {
        ModalBottomSheet(
            onDismissRequest = { addOpen = false },
            sheetState = sheetState,
            containerColor = Palette.bg,
            dragHandle = {
                Box(Modifier.padding(top = 12.dp, bottom = 4.dp).size(40.dp, 4.dp).clip(CircleShape).background(Palette.line))
            },
        ) {
            Kicker("Add to the vault", Modifier.padding(horizontal = Gutter, vertical = 12.dp))
            AddOption(LineIcons.photo, "Photos", "From your gallery") {
                addOpen = false
                onImportMedia(PickKind.IMAGES)
            }
            AddOption(LineIcons.video, "Videos", "From your gallery") {
                addOpen = false
                onImportMedia(PickKind.VIDEOS)
            }
            AddOption(LineIcons.file, "Files", "Documents, PDFs, anything else") {
                addOpen = false
                VaultSession.expectingExternalActivity = true
                pickDocuments.launch(arrayOf("*/*"))
            }
            AddOption(LineIcons.inbox, "A shared file", "Something sent to you, or a backup") {
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
                    val errors = withContext(Dispatchers.IO) {
                        chosen.mapNotNull { VaultExport.putBackOnPhone(context, vault, it).error }
                    }
                    busy = null
                    note = when {
                        errors.isEmpty() && chosen.size == 1 -> "Back on your phone."
                        errors.isEmpty() -> "${chosen.size} back on your phone."
                        // Say what actually went wrong. A bare count sends the next
                        // person guessing, which is exactly what happened here.
                        else -> "${errors.size} could not be written: ${errors.first()}"
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
            text = { CircularProgressIndicator(color = Palette.accent) },
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
                }) { Text("Delete", color = Palette.danger) }
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
    // Picked tiles shrink a little inside an accent frame, the way a gallery does it.
    val inset by animateDpAsState(if (isSelected) 8.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "pick")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (isSelected) Palette.accent else Palette.card2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (isSelected) 10.dp else 14.dp)).background(Palette.card2)) {
            if (kind == ItemKind.FILE) {
                // A document has no picture to show, so it shows what it is instead. A grey
                // square with nothing on it is indistinguishable from one that failed.
                Column(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(LineIcons.file, contentDescription = null, tint = Palette.accentText, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.name,
                        color = Palette.dim,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
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
            if (kind == ItemKind.VIDEO) {
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

/** The bar shown while items are picked: accent-filled so it can't be mistaken for the usual one. */
@Composable
private fun SelectionBar(
    count: Int,
    allPicked: Boolean,
    onCancel: () -> Unit,
    onAll: () -> Unit,
    onShare: () -> Unit,
    onPutBack: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 6.dp)
            .clip(blockShape).background(Palette.accent).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(LineIcons.close, "Cancel", onCancel)
        Text(
            "$count", style = display(20), color = Palette.onAccent,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        Text(
            if (allPicked) "None" else "All",
            color = Palette.onAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onAll).padding(horizontal = 12.dp, vertical = 10.dp),
        )
        BarIcon(LineIcons.share, "Share", onShare)
        BarIcon(LineIcons.putBack, "Put back on phone", onPutBack)
        BarIcon(LineIcons.trash, "Delete", onDelete)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Palette.onAccent, modifier = Modifier.size(20.dp)) }
}

/** The round accent button that opens the add sheet. */
@Composable
private fun AddButton(modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.size(62.dp).pressScale(src).clip(CircleShape).background(Palette.accent)
            .clickable(interactionSource = src, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(LineIcons.plus, "Add", tint = Palette.onAccent, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun OfferCard(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    onNever: (() -> Unit)? = null,
) {
    Block(Modifier.padding(horizontal = Gutter, vertical = 8.dp).enter(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Palette.card2),
                contentAlignment = Alignment.Center,
            ) { Icon(LineIcons.fingerprint, null, tint = Palette.accentText, modifier = Modifier.size(20.dp)) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(detail, color = Palette.dim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(action, modifier = Modifier.height(40.dp), onClick = onAction)
            Spacer(Modifier.width(4.dp))
            TextAction("Not now", color = Palette.dim, onClick = onDismiss)
            onNever?.let { TextAction("Never", color = Palette.dim, onClick = it) }
        }
    }
}

@Composable
private fun AddOption(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp).pressScale(src)
            .clip(blockShape).background(Palette.panel)
            .clickable(interactionSource = src, indication = ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Palette.card2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.accentText, modifier = Modifier.size(20.dp)) }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(detail, color = Palette.dim, fontSize = 13.sp)
        }
        Icon(LineIcons.chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
    }
}
