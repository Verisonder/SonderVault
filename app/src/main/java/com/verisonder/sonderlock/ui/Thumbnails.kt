package com.verisonder.sonderlock.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.verisonder.sonderlock.media.DeviceMediaItem
import com.verisonder.sonderlock.media.MediaImporter
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
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

@Composable
fun rememberVaultThumbnail(vault: Vault, item: VaultItem): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                vault.readThumbnail(item)?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
