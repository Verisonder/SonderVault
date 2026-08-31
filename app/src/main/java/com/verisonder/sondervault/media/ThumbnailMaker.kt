package com.verisonder.sondervault.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.verisonder.sondervault.crypto.VaultFileReader
import com.verisonder.sondervault.vault.ItemKind
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem
import java.io.ByteArrayOutputStream

/**
 * Builds a thumbnail for something already in the vault.
 *
 * Needed because a thumbnail is not always there. Items restored from a bundle arrive
 * without one, and a video frame can fail to decode at import time. Rather than leaving a
 * grey square in the grid forever, the missing one is made from the item itself and
 * stored alongside it.
 *
 * Nothing is written out in the clear on the way. Images are decoded from bytes held in
 * memory; video is read through a MediaDataSource that decrypts on demand, so the frame
 * grabber seeks around inside the encrypted file the same way the player does.
 */
object ThumbnailMaker {

    private const val EDGE = 512
    private const val QUALITY = 80

    fun make(vault: Vault, item: VaultItem): ByteArray? = runCatching {
        // A document has no frame to grab. Attempting one reads the whole file back
        // through AES to hand a PDF to a bitmap decoder that will refuse it.
        val bitmap = when (ItemKind.of(item.mimeType)) {
            ItemKind.VIDEO -> videoFrame(vault, item)
            ItemKind.IMAGE -> stillImage(vault, item)
            ItemKind.FILE -> null
        }
        bitmap?.let { compress(it) }
    }.getOrNull()

    private fun stillImage(vault: Vault, item: VaultItem): Bitmap? {
        val bytes = vault.open(item).use { reader ->
            ByteArrayOutputStream().also { reader.copyTo(it) }.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= EDGE && bounds.outHeight / (sample * 2) >= EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun videoFrame(vault: Vault, item: VaultItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val reader = vault.open(item)
        return try {
            retriever.setDataSource(EncryptedSource(reader))
            retriever.getScaledFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                EDGE,
                EDGE,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { reader.close() }
        }
    }

    /**
     * The frame grabber reads at arbitrary offsets, which is exactly what the container
     * format was built for: each read decrypts the one block it lands in.
     */
    private class EncryptedSource(private val reader: VaultFileReader) : MediaDataSource() {

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= reader.plainSize) return -1
            val bytes = reader.read(position, size)
            if (bytes.isEmpty()) return -1
            System.arraycopy(bytes, 0, buffer, offset, bytes.size)
            return bytes.size
        }

        override fun getSize(): Long = reader.plainSize

        // Closed by the caller, which also owns the reader.
        override fun close() = Unit
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
