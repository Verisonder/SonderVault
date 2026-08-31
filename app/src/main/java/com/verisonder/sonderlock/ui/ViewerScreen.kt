package com.verisonder.sonderlock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_IMAGE_EDGE = 2560

/**
 * One item at a time, swipe to move between them.
 *
 * Neither path writes a decrypted copy anywhere. Images are decoded into memory; video is
 * read by the player through VaultDataSource, block by block, as it plays.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ViewerScreen(
    vault: Vault,
    items: List<VaultItem>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) {
        onClose()
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var confirming by remember { mutableStateOf<Action?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            if (item.mimeType.startsWith("video/")) {
                VideoPage(vault, item, isCurrent = pager.currentPage == page)
            } else {
                ImagePage(vault, item)
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onClose, enabled = busy == null) { Text("Close") }
                Row {
                    TextButton(
                        onClick = { confirming = Action.PutBack },
                        enabled = busy == null,
                    ) { Text("Put back on phone") }
                    TextButton(
                        onClick = { confirming = Action.Delete },
                        enabled = busy == null,
                    ) { Text("Delete") }
                }
            }
            busy?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }

    val pending = confirming
    if (pending != null) {
        val item = items[pager.currentPage.coerceIn(0, items.lastIndex)]
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (pending == Action.PutBack) "Put back on phone?" else "Delete?") },
            text = {
                Text(
                    if (pending == Action.PutBack) {
                        "It returns to your gallery as an ordinary file and leaves the vault."
                    } else {
                        "This cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    busy = if (pending == Action.PutBack) "Writing…" else "Deleting…"
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            if (pending == Action.PutBack) {
                                VaultExport.putBackOnPhone(context, vault, item).error
                            } else {
                                runCatching { vault.delete(item) }.exceptionOrNull()?.message
                            }
                        }
                        busy = null
                        problem = error
                        if (error == null) {
                            VaultSession.noteContentsChanged()
                            onClose()
                        }
                    }
                }) { Text(if (pending == Action.PutBack) "Put back" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

private enum class Action { PutBack, Delete }

@Composable
private fun ImagePage(vault: Vault, item: VaultItem) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeFull(vault, item) }.getOrNull()
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ready = bitmap
        if (ready == null) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White)
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
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(VaultDataSource.Factory(vault, item)),
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(VaultDataSource.uriFor(item)))
                prepare()
            }
    }

    // Released when the page leaves, otherwise every swipe leaves a player holding a
    // decrypted read open behind it.
    DisposableEffect(item.id) {
        onDispose { player.release() }
    }

    // Swiping away should stop the sound, which otherwise carries on from a page nobody
    // is looking at.
    DisposableEffect(isCurrent) {
        player.playWhenReady = isCurrent
        onDispose { player.playWhenReady = false }
    }

    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize(),
    )
}
