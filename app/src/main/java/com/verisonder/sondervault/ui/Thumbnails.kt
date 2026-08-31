package com.verisonder.sondervault.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.verisonder.sondervault.media.DeviceMediaItem
import com.verisonder.sondervault.media.MediaImporter
import com.verisonder.sondervault.media.ThumbnailMaker
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnails, decoded off the main thread and held only in memory.
 *
 * Nothing here writes a cache to disk. A decoded copy of a hidden photo sitting in a
 * cache directory would be the same leak the vault exists to prevent, and it would
 * outlive the app being locked.
 */
@Composable
fun rememberDeviceThumbnail(item: DeviceMediaItem): State<ImageBitmap?> {
    val context: Context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) {
            MediaImporter.deviceThumbnail(context, item)?.asImageBitmap()
        }
    }
}

/**
 * A tile's thumbnail, made on the spot if the item does not have one.
 *
 * Items restored from a bundle arrive without thumbnails, and a video frame can fail to
 * decode at import. Regenerating here means a grey square fixes itself the first time it
 * is looked at, rather than staying grey for the life of the vault.
 */
@Composable
fun rememberVaultThumbnail(vault: Vault, item: VaultItem): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stored = vault.readThumbnail(item)
                val bytes = stored ?: ThumbnailMaker.make(vault, item)?.also {
                    runCatching { vault.attachThumbnail(item, it) }
                }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
        }
    }
