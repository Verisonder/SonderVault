package com.verisonder.sonderlock.media

import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
import java.io.ByteArrayOutputStream

/**
 * Bringing a file in from the phone, and taking the original away afterwards.
 *
 * Import and deletion are separate on purpose and always happen in that order. If the
 * copy fails the original is still there; the reverse would lose the photo.
 */
object MediaImporter {

    private const val THUMBNAIL_EDGE = 512
    private const val THUMBNAIL_QUALITY = 80

    class Outcome(val imported: List<VaultItem>, val importedUris: List<Uri>, val failures: List<String>)

    fun importAll(context: Context, vault: Vault, selected: List<DeviceMediaItem>): Outcome {
        val imported = ArrayList<VaultItem>()
        val uris = ArrayList<Uri>()
        val failures = ArrayList<String>()

        for (item in selected) {
            val result = runCatching {
                val thumbnail = thumbnailFor(context, item)
                context.contentResolver.openInputStream(item.uri).use { stream ->
                    requireNotNull(stream) { "could not read ${item.name}" }
                    vault.importItem(
                        name = item.name,
                        mimeType = item.mimeType,
                        input = stream,
                        capturedAt = item.takenAt,
                        thumbnail = thumbnail,
                    )
                }
            }
            result
                .onSuccess { imported.add(it); uris.add(item.uri) }
                .onFailure { failures.add(item.name) }
        }
        return Outcome(imported, uris, failures)
    }

    /**
     * The request to remove the originals.
     *
     * On Android 12 and above with media management granted this completes without a
     * dialog. Without it, the same call shows one confirmation for the whole batch rather
     * than one per file.
     */
    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }
        // Android 9 and 10 predate the batch request; a direct delete works there because
        // the app holds storage permission and legacy storage is on.
        for (uri in uris) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        return null
    }

    // -------------------------------------------------------------------- thumbnails

    /**
     * Generated here, from the original, rather than taken from MediaStore's cache. A
     * cached system thumbnail survives the original being deleted and sits in a folder
     * this app does not control, which would leave a small picture of every hidden photo
     * outside the vault.
     */
    private fun thumbnailFor(context: Context, item: DeviceMediaItem): ByteArray? = runCatching {
        val bitmap = if (item.isVideo) videoFrame(context, item.uri) else scaledImage(context, item.uri)
        bitmap?.let { compress(it) }
    }.getOrNull()

    private fun scaledImage(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Powers of two, because inSampleSize rounds down to one anyway and decoding at
        // full size to shrink afterwards is what makes importing a large album crawl.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMBNAIL_EDGE &&
            bounds.outHeight / (sample * 2) >= THUMBNAIL_EDGE
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun videoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMBNAIL_EDGE,
                    THUMBNAIL_EDGE,
                )
            } else {
                retriever.frameAtTime
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** For the picker grid: the system's own thumbnail of something still on the phone. */
    fun deviceThumbnail(context: Context, item: DeviceMediaItem): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
        } else if (item.isVideo) {
            videoFrame(context, item.uri)
        } else {
            scaledImage(context, item.uri)
        }
    }.getOrNull()
}
