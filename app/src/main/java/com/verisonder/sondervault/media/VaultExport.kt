package com.verisonder.sondervault.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem
import java.io.File
import java.io.FileOutputStream

/**
 * The way back out. An item leaves the vault and becomes an ordinary photo or video
 * again, as though it had never been hidden.
 *
 * Written first, removed second. If the write fails the item is still in the vault, which
 * is the right way round: the alternative loses it.
 */
object VaultExport {

    class Result(val uri: Uri?, val error: String?)

    fun putBackOnPhone(context: Context, vault: Vault, item: VaultItem): Result {
        val isVideo = item.mimeType.startsWith("video/")
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeThroughMediaStore(context, vault, item, isVideo)
            } else {
                writeThroughFile(context, vault, item, isVideo)
            }
            // Only now, and only if the bytes are actually somewhere else.
            vault.delete(item)
            Result(uri, null)
        } catch (e: Exception) {
            Result(null, e.message ?: "Could not write the file")
        }
    }

    private fun writeThroughMediaStore(
        context: Context,
        vault: Vault,
        item: VaultItem,
        isVideo: Boolean,
    ): Uri {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            // Put back where it was in the timeline, not at the top of the gallery. A
            // photo from three years ago that reappears as today's is worse than not
            // having it back at all — it is lost in a different way.
            //
            // DATE_TAKEN is what gallery apps sort on and is in milliseconds;
            // DATE_MODIFIED is the fallback and is in seconds.
            put(MediaStore.MediaColumns.DATE_TAKEN, item.capturedAt)
            put(MediaStore.MediaColumns.DATE_MODIFIED, item.capturedAt / 1000)
            // Hidden from the gallery until the bytes are all there, so no half-written
            // photo ever appears in someone's camera roll.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("The gallery would not accept the file")

        try {
            resolver.openOutputStream(uri).use { sink ->
                requireNotNull(sink) { "could not open the file for writing" }
                vault.open(item).use { reader -> reader.copyTo(sink) }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    private fun writeThroughFile(
        context: Context,
        vault: Vault,
        item: VaultItem,
        isVideo: Boolean,
    ): Uri {
        val folder = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        folder.mkdirs()
        val target = uniqueName(folder, item.name)
        FileOutputStream(target).use { sink ->
            vault.open(item).use { reader -> reader.copyTo(sink) }
        }
        // The only handle on ordering before Android 10 is the file's own timestamp.
        target.setLastModified(item.capturedAt)
        // Nothing indexes this automatically before Android 10, so the gallery would not
        // show the file until something else happened to trigger a scan.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(item.mimeType), null)
        return Uri.fromFile(target)
    }

    private fun uniqueName(folder: File, name: String): File {
        var candidate = File(folder, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists()) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            candidate = File(folder, "$stem ($n)$suffix")
            n++
        }
        return candidate
    }
}
