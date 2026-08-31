package com.verisonder.sonderlock.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * One photo or video already on the phone, as MediaStore describes it.
 *
 * The uri is a MediaStore item uri, and that matters: it is the only kind that can be
 * passed to `createDeleteRequest`. The system photo picker hands back a different sort
 * that cannot be deleted, which is why this app queries MediaStore itself and shows its
 * own grid rather than using the picker.
 */
data class DeviceMediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val takenAt: Long,
    val isVideo: Boolean,
)

object DeviceMedia {

    fun query(context: Context, limit: Int = 2000): List<DeviceMediaItem> {
        val images = queryOne(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        val videos = queryOne(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return (images + videos).sortedByDescending { it.takenAt }.take(limit)
    }

    private fun queryOne(context: Context, collection: Uri, isVideo: Boolean): List<DeviceMediaItem> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = ArrayList<DeviceMediaItem>()
        context.contentResolver.query(
            collection,
            columns,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                out.add(
                    DeviceMediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameColumn) ?: "untitled",
                        mimeType = cursor.getString(mimeColumn)
                            ?: if (isVideo) "video/*" else "image/*",
                        size = cursor.getLong(sizeColumn),
                        // DATE_MODIFIED is in seconds where everything else here is in
                        // milliseconds, which is an easy thousand-fold mistake to make.
                        takenAt = cursor.getLong(dateColumn) * 1000L,
                        isVideo = isVideo,
                    )
                )
            }
        }
        return out
    }
}
