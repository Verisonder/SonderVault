package com.verisonder.sonderlock.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import com.verisonder.sonderlock.crypto.VaultFileReader
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * Reading a document without ever writing it out.
 *
 * PdfRenderer insists on a real file descriptor it can seek in, which normally means
 * decrypting to a temporary file — a plaintext copy of the thing the vault exists to
 * hide, sitting in the cache directory for as long as the system feels like keeping it.
 *
 * A proxy file descriptor avoids that entirely. The renderer gets something that behaves
 * like a file, every read is served from the encrypted container on demand, and nothing
 * is ever written anywhere.
 */
object DocumentPreview {

    private const val TEXT_LIMIT = 1 shl 20

    fun isText(mimeType: String): Boolean =
        mimeType.startsWith("text/") ||
            mimeType == "application/json" ||
            mimeType == "application/xml" ||
            mimeType == "application/x-yaml"

    fun isPdf(mimeType: String): Boolean = mimeType == "application/pdf"

    fun canPreview(mimeType: String): Boolean = isText(mimeType) || isPdf(mimeType)

    /** Up to a megabyte. Past that nobody is reading it on a phone anyway. */
    fun text(vault: Vault, item: VaultItem): String? = runCatching {
        vault.open(item).use { reader ->
            val out = ByteArrayOutputStream()
            reader.copyTo(out, 0, minOf(reader.plainSize, TEXT_LIMIT.toLong()))
            val body = String(out.toByteArray(), Charsets.UTF_8)
            if (item.size > TEXT_LIMIT) body + "\n\n…" else body
        }
    }.getOrNull()

    fun openPdf(context: Context, vault: Vault, item: VaultItem): Pdf? = runCatching {
        Pdf(context, vault, item)
    }.getOrNull()

    /**
     * An open PDF. Not thread safe, because PdfRenderer is not: only one page can be
     * open at a time, so rendering is serialised here rather than left to chance.
     */
    class Pdf internal constructor(
        context: Context,
        vault: Vault,
        item: VaultItem,
    ) : Closeable {

        private val reader: VaultFileReader = vault.open(item)
        private val thread = HandlerThread("sonderlock-pdf").apply { start() }
        private val descriptor: ParcelFileDescriptor
        private val renderer: PdfRenderer

        init {
            val storage = context.getSystemService(StorageManager::class.java)
            descriptor = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                Callback(reader),
                Handler(thread.looper),
            )
            renderer = PdfRenderer(descriptor)
        }

        val pageCount: Int get() = renderer.pageCount

        @Synchronized
        fun render(index: Int, width: Int): Bitmap? = runCatching {
            renderer.openPage(index).use { page ->
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Pages are transparent where nothing is drawn, which on a dark theme
                // renders black text on black. Paper is white; say so.
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()

        @Synchronized
        override fun close() {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private class Callback(private val reader: VaultFileReader) : ProxyFileDescriptorCallback() {

        override fun onGetSize(): Long = reader.plainSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val bytes = reader.read(offset, size)
            System.arraycopy(bytes, 0, data, 0, bytes.size)
            return bytes.size
        }

        // The reader is owned by Pdf, which closes it. Closing here as well would shut it
        // underneath a render already in flight.
        override fun onRelease() = Unit
    }
}
