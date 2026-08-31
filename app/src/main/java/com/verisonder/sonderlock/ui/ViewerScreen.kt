package com.verisonder.sonderlock.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.verisonder.sonderlock.R
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
    val currentIsVideo = current.mimeType.startsWith("video/")

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
            if (item.mimeType.startsWith("video/")) {
                VideoPage(
                    vault = vault,
                    item = item,
                    isCurrent = isCurrent,
                    onControlsVisible = { if (isCurrent) chrome = it },
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
                    // On a video the player draws its own scrubber and clock along the
                    // bottom. Sitting on top of them is what made this unreadable before,
                    // so on video pages these actions move up out of that strip rather
                    // than sharing it.
                    .padding(
                        top = 8.dp,
                        bottom = if (currentIsVideo) 72.dp else 8.dp,
                    ),
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
 * Video pages let the player own the tap.
 *
 * PlayerView handles its own touches and shows or hides its controller on them, so a tap
 * handler layered on top never fires — which is why the bars sat there and would not go
 * away. Instead of fighting it, the bars follow the controller: whatever the player
 * decides is visible, the rest of the screen matches.
 *
 * The cost is that pinch to zoom does not work on video, since the gestures never reach
 * this side. Worth saying plainly rather than pretending it does.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    vault: Vault,
    item: VaultItem,
    isCurrent: Boolean,
    onControlsVisible: (Boolean) -> Unit,
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
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = true
                controllerAutoShow = true
                controllerHideOnTouch = true
                // Never on a timer. The controller goes away when tapped and not before,
                // so the bars do not vanish out from under a finger.
                controllerShowTimeoutMs = 0
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        onControlsVisible(visibility == android.view.View.VISIBLE)
                    }
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun dayOf(millis: Long): String =
    SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(millis))

private fun timeOf(millis: Long): String =
    SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(millis))
