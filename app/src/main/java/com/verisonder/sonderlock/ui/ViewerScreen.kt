package com.verisonder.sonderlock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.verisonder.sonderlock.media.VaultDataSource
import com.verisonder.sonderlock.media.VaultExport
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_IMAGE_EDGE = 2560

/**
 * One item at a time, swipe to move between them.
 *
 * The actions live in an overflow menu in the app bar, not in a row along the bottom.
 * The bottom belongs to the player: its timeline, scrubber and clock all sit there, and
 * the earlier version put buttons on top of them so the two were unreadable together.
 * Anything permanent at the bottom of a video screen is in the player's way.
 *
 * Neither path writes a decrypted copy anywhere. Images are decoded into memory; video is
 * read by the player through VaultDataSource, block by block, as it plays.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    store: VaultStore,
    vault: Vault,
    items: List<VaultItem>,
    startIndex: Int,
    onShare: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Action?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Scaffold(
        containerColor = Color.Black,
        contentColor = Color.White,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(current.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = busy == null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }, enabled = busy == null) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { menuOpen = false; onShare(listOf(current.id)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Put back on phone") },
                            onClick = { menuOpen = false; confirming = Action.PutBack },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; confirming = Action.Delete },
                        )
                    }
                },
                // Floated over the photo rather than sitting on a bar of its own, so the
                // image keeps the whole screen.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                if (item.mimeType.startsWith("video/")) {
                    VideoPage(vault, item, isCurrent = pager.currentPage == page)
                } else {
                    ImagePage(vault, item)
                }
            }
            busy?.let {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            // Consumed so the pager fills the screen; the bar floats above it.
            Box(modifier = Modifier.padding(padding))
        }
    }

    val pending = confirming
    if (pending != null) {
        fun run(label: String, work: () -> String?) {
            confirming = null
            busy = label
            scope.launch {
                val error = withContext(Dispatchers.IO) { work() }
                busy = null
                if (error == null) {
                    VaultSession.noteContentsChanged()
                    onClose()
                } else {
                    snackbar.showSnackbar(error)
                }
            }
        }

        if (pending == Action.PutBack) {
            ConfirmPassword(
                store = store,
                vault = vault,
                reason = "This writes the file back to your gallery, where anything on " +
                    "the phone can read it.",
                confirmLabel = "Put back",
                onConfirmed = {
                    run("Writing") { VaultExport.putBackOnPhone(context, vault, current).error }
                },
                onCancel = { confirming = null },
            )
        } else {
            AlertDialog(
                onDismissRequest = { confirming = null },
                title = { Text("Delete?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        run("Deleting") {
                            runCatching { vault.delete(current) }.exceptionOrNull()?.message
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
            )
        }
    }
}

private enum class Action { PutBack, Delete }

@Composable
private fun ImagePage(vault: Vault, item: VaultItem) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { decodeFull(vault, item) }.getOrNull() }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ready = bitmap
        if (ready == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = ready,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Decoded twice: once for the dimensions, once for the pixels. A modern phone photo
 * decoded at full size is tens of megabytes of bitmap for a screen that cannot show a
 * fraction of it, and doing that for several pages of a pager runs the app out of memory.
 */
private fun decodeFull(vault: Vault, item: VaultItem): ImageBitmap? {
    val bytes = vault.open(item).use { reader ->
        ByteArrayOutputStream().also { reader.copyTo(it) }.toByteArray()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_EDGE ||
        bounds.outHeight / (sample * 2) >= MAX_IMAGE_EDGE
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(vault: Vault, item: VaultItem, isCurrent: Boolean) {
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultDataSource.Factory(vault, item)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultDataSource.uriFor(item)))
                prepare()
            }
    }

    // Released when the page leaves, otherwise every swipe leaves a player holding a
    // decrypted read open behind it.
    DisposableEffect(item.id) { onDispose { player.release() } }

    // Swiping away stops the sound, which otherwise carries on from a page nobody is
    // looking at.
    DisposableEffect(isCurrent) {
        player.playWhenReady = isCurrent
        onDispose { player.playWhenReady = false }
    }

    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize(),
    )
}
