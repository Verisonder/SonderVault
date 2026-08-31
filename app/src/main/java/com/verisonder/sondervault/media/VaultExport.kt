package com.verisonder.sondervault.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.ItemKind
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
        val kind = ItemKind.of(item.mimeType)
        return try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeThroughMediaStore(context, vault, item, kind)
            } else {
                writeThroughFile(context, vault, item, kind)
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
        kind: ItemKind,
    ): Uri {
        // A document is not a photo and MediaStore knows it: handing application/pdf to
        // the Images collection is rejected outright, which is what made putting a file
        // back fail with nothing useful to say.
        val collection = when (kind) {
            ItemKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ItemKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ItemKind.FILE -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = when (kind) {
            ItemKind.VIDEO -> Environment.DIRECTORY_MOVIES
            ItemKind.IMAGE -> Environment.DIRECTORY_PICTURES
            ItemKind.FILE -> Environment.DIRECTORY_DOWNLOADS
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            // Put back where it was in the timeline, not at the top of the gallery. A
            // photo from three years ago that reappears as today's is worse than not
            // having it back at all — it is lost in a different way.
            //
            // DATE_TAKEN is what gallery apps sort on and is in milliseconds;
            // DATE_MODIFIED is the fallback and is in seconds. Only media carries a
            // capture time, and offering the column for anything else is a way to have
            // the insert refused.
            if (kind != ItemKind.FILE) put(MediaStore.MediaColumns.DATE_TAKEN, item.capturedAt)
            put(MediaStore.MediaColumns.DATE_MODIFIED, item.capturedAt / 1000)
            // Hidden from the gallery until the bytes are all there, so no half-written
            // photo ever appears in someone's camera roll.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = insert(resolver, collection, values)

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

    /**
     * Some devices refuse DATE_TAKEN on a volume that does not carry it, and the insert
     * throws rather than ignoring the column. Losing the timeline position is a great
     * deal better than losing the file, so the second attempt drops it.
     */
    private fun insert(
        resolver: android.content.ContentResolver,
        collection: Uri,
        values: ContentValues,
    ): Uri {
        runCatching { resolver.insert(collection, values) }
            .getOrNull()
            ?.let { return it }
        values.remove(MediaStore.MediaColumns.DATE_TAKEN)
        return resolver.insert(collection, values)
            ?: throw IllegalStateException("Android would not accept the file")
    }

    private fun writeThroughFile(
        context: Context,
        vault: Vault,
        item: VaultItem,
        kind: ItemKind,
    ): Uri {
        val folder = Environment.getExternalStoragePublicDirectory(
            when (kind) {
                ItemKind.VIDEO -> Environment.DIRECTORY_MOVIES
                ItemKind.IMAGE -> Environment.DIRECTORY_PICTURES
                ItemKind.FILE -> Environment.DIRECTORY_DOWNLOADS
            },
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
