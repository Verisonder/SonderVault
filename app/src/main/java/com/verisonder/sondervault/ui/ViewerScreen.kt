package com.verisonder.sondervault.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import com.verisonder.sondervault.media.DocumentPreview
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.verisonder.sondervault.R
import com.verisonder.sondervault.media.VaultDataSource
import com.verisonder.sondervault.media.VaultExport
import com.verisonder.sondervault.vault.ItemKind
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_IMAGE_EDGE = 2560
private const val MAX_ZOOM = 6f

/**
 * One item at a time, swipe to move between them.
 *
 * Tapping the photo hides the bars and shows them again, which is what every gallery
 * does and what makes a bottom bar workable at all: the actions and the video's own
 * scrubber never have to share the same strip, because a tap that reveals one reveals
 * both and a tap that hides one hides both.
 *
 * Neither path writes a decrypted copy anywhere. Images are decoded into memory; video is
 * read by the player through VaultDataSource, block by block, as it plays.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ViewerScreen(
    store: VaultStore,
    vault: Vault,
    activity: FragmentActivity,
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
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingPutBack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]


    // Zooming out of one photo should not leave the next one stuck.
    LaunchedEffect(pager.currentPage) { zoomed = false }

    fun run(label: String, work: () -> String?) {
        busy = label
        scope.launch {
            val error = withContext(Dispatchers.IO) { work() }
            busy = null
            if (error == null) {
                VaultSession.noteContentsChanged()
                onClose()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            // A pinched-in photo has to be pannable, and panning it is the same gesture
            // as turning the page.
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val isCurrent = pager.currentPage == page
            if (ItemKind.of(item.mimeType) == ItemKind.FILE) {
                DocumentPage(vault, item) { chrome = !chrome }
            } else if (ItemKind.of(item.mimeType) == ItemKind.VIDEO) {
                VideoPage(
                    vault = vault,
                    item = item,
                    isCurrent = isCurrent,
                    controlsVisible = chrome,
                    onTap = { chrome = !chrome },
                    onZoomChanged = { if (isCurrent) zoomed = it },
                )
            } else {
                ImagePage(
                    vault = vault,
                    item = item,
                    onTap = { chrome = !chrome },
                    onZoomChanged = { if (isCurrent) zoomed = it },
                )
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, enabled = busy == null) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Column {
                        Text(
                            dayOf(current.capturedAt),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            timeOf(current.capturedAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = { onShare(listOf(current.id)) },
                    enabled = busy == null,
                ) { Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White) }

                IconButton(
                    onClick = { confirmingPutBack = true },
                    enabled = busy == null,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_put_back),
                        contentDescription = "Put back on phone",
                        tint = Color.White,
                    )
                }

                IconButton(
                    onClick = { confirmingDelete = true },
                    enabled = busy == null,
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White) }
            }
        }

        if (busy != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }
        }
    }

    if (confirmingPutBack) {
        ConfirmPassword(
            store = store,
            vault = vault,
            activity = activity,
            reason = "This writes the file back to your gallery, where anything on the " +
                "phone can read it.",
            confirmLabel = "Put back",
            onConfirmed = {
                confirmingPutBack = false
                run("Writing") { VaultExport.putBackOnPhone(context, vault, current).error }
            },
            onCancel = { confirmingPutBack = false },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    run("Deleting") {
                        runCatching { vault.delete(current) }.exceptionOrNull()?.message
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Pinch to zoom, drag to pan, double tap to toggle.
 *
 * Panning is clamped to the edges of the scaled image, so it cannot be dragged off into
 * empty space and lost — the usual way a hand-rolled zoom feels broken.
 */
@Composable
private fun Modifier.zoomable(
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
): Modifier {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp() {
        val maxX = (size.width * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (size.height * (scale - 1f) / 2f).coerceAtLeast(0f)
        offset = Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY),
        )
    }

    return this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                    onZoomChanged(scale > 1f)
                },
            )
        }
        .pointerInput(Unit) {
            // Hand-rolled rather than detectTransformGestures, which consumes every
            // pointer change including a plain one-finger drag — so the pager never saw
            // a swipe and pages stopped turning. Events are only taken when there are
            // two fingers down, or when the photo is already zoomed in and a drag means
            // panning rather than turning the page.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val pinching = event.changes.size > 1
                    if (pinching || scale > 1f) {
                        scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                        offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                        clamp()
                        onZoomChanged(scale > 1f)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}

/**
 * Documents that can be read are read here.
 *
 * Text is decoded straight from the container. A PDF is rendered by PdfRenderer through
 * a proxy file descriptor, so it seeks around inside the encrypted file exactly as the
 * video player does — the usual approach of decrypting to a temporary file would leave a
 * plaintext copy of the document in the cache directory for as long as the system felt
 * like keeping it.
 *
 * Anything else has nothing to show, so the page says what it is and leaves the actions
 * to do the work.
 */
@Composable
private fun DocumentPage(vault: Vault, item: VaultItem, onTap: () -> Unit) {
    val tap = Modifier.pointerInput(item.id) { detectTapGestures(onTap = { onTap() }) }
    when {
        DocumentPreview.isText(item.mimeType) -> TextDocument(vault, item, tap)
        DocumentPreview.isPdf(item.mimeType) -> PdfDocument(vault, item, tap)
        else -> UnreadableDocument(item, tap)
    }
}

@Composable
private fun TextDocument(vault: Vault, item: VaultItem, tap: Modifier) {
    val body by produceState<String?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { DocumentPreview.text(vault, item) ?: "" }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).then(tap)) {
        val text = body
        if (text == null) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 96.dp),
            )
        }
    }
}

@Composable
private fun PdfDocument(vault: Vault, item: VaultItem, tap: Modifier) {
    val context = LocalContext.current
    var pdf by remember(item.id) { mutableStateOf<DocumentPreview.Pdf?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }

    DisposableEffect(item.id) {
        val opened = DocumentPreview.openPdf(context, vault, item)
        pdf = opened
        failed = opened == null
        onDispose {
            opened?.close()
            pdf = null
        }
    }

    val open = pdf
    when {
        failed -> UnreadableDocument(item, tap)
        open == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black).then(tap),
            contentPadding = PaddingValues(vertical = 96.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(open.pageCount) { index ->
                // Rendered as the page scrolls into view rather than all at once. A
                // two hundred page document would otherwise be two hundred bitmaps.
                val page by produceState<ImageBitmap?>(initialValue = null, item.id, index) {
                    value = withContext(Dispatchers.IO) {
                        open.render(index, 1080)?.asImageBitmap()
                    }
                }
                val ready = page
                if (ready == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Color.White) }
                } else {
                    Image(
                        bitmap = ready,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnreadableDocument(item: VaultItem, tap: Modifier) {
    Box(
        modifier = Modifier.fillMaxSize().then(tap),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_file),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${item.size / 1024} KB",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "SonderVault cannot show this kind of file. Put it back on your phone to " +
                    "open it.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ImagePage(
    vault: Vault,
    item: VaultItem,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
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
                modifier = Modifier.fillMaxSize().zoomable(onTap, onZoomChanged),
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

/**
 * Video pages draw their own controls.
 *
 * PlayerView's built-in controller was tried three ways and none of them worked: a
 * Compose gesture on top never fires, watching the touch only sees the taps the
 * controller does not consume, and following the controller's own visibility left the
 * app's bars stuck hidden. The overlay consumes a tap when it is visible and ignores one
 * when it is not, so anything layered around it is permanently half a tap out of step.
 *
 * With useController off, PlayerView is just a surface and Compose owns every touch.
 * There is nothing left to keep in sync, and pinch to zoom works on video as a result.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    vault: Vault,
    item: VaultItem,
    isCurrent: Boolean,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
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

    var playing by remember(item.id) { mutableStateOf(false) }
    var position by remember(item.id) { mutableLongStateOf(0L) }
    var duration by remember(item.id) { mutableLongStateOf(0L) }
    var scrubbing by remember(item.id) { mutableStateOf(false) }

    // Released when the page leaves, otherwise every swipe leaves a player holding a
    // decrypted read open behind it.
    DisposableEffect(item.id) { onDispose { player.release() } }

    // Swiping away stops the sound, which otherwise carries on from a page nobody is
    // looking at.
    DisposableEffect(isCurrent) {
        player.playWhenReady = isCurrent
        onDispose { player.playWhenReady = false }
    }

    // Polled rather than driven by listeners. Two hundred milliseconds is under what
    // anyone notices on a scrubber, and it avoids keeping several listeners alive across
    // a pager that is creating and destroying players as it goes.
    LaunchedEffect(item.id, isCurrent) {
        while (isCurrent) {
            playing = player.isPlaying
            duration = player.duration.coerceAtLeast(0L)
            if (!scrubbing) position = player.currentPosition.coerceAtLeast(0L)
            kotlinx.coroutines.delay(200)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    // The whole reason this works: with no controller, the view stops
                    // competing for touches.
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize().zoomable(onTap, onZoomChanged),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    // Clear of the action bar below, which is where share, put back and
                    // delete live.
                    .padding(bottom = 64.dp)
                    // The same scrim the actions sit on, so the two read as one bar. Over
                    // a bright frame the white controls were invisible without it — the
                    // three buttons had a backing and these did not.
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    playing = !playing
                }) {
                    Icon(
                        painterResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
                Text(
                    clock(position),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = {
                        scrubbing = true
                        position = (it * duration).toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(position)
                        scrubbing = false
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    clock(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

private fun clock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun dayOf(millis: Long): String =
    SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(millis))

private fun timeOf(millis: Long): String =
    SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(millis))
