package com.verisonder.sonderlock.vault

import com.verisonder.sonderlock.crypto.ByteArraySource
import com.verisonder.sonderlock.crypto.Crypto
import com.verisonder.sonderlock.crypto.FileSource
import com.verisonder.sonderlock.crypto.VaultFileReader
import com.verisonder.sonderlock.crypto.VaultFileWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * A vault that has been opened. Holding one of these means the master key is in memory,
 * so it is closed as soon as the app goes to the background.
 *
 * There is nothing here that knows whether it is the real vault or the decoy. Both are
 * ordinary vaults and the code paths are identical — a decoy that behaved differently
 * would eventually behave differently somewhere visible.
 */
class Vault internal constructor(
    val directory: File,
    private val masterKey: ByteArray,
) : Closeable {

    private val indexKey = Crypto.hkdf(masterKey, INFO_INDEX)
    private val itemsDir = File(directory, "items").apply { mkdirs() }
    private val indexFile = File(directory, "index")

    private var cache: MutableList<VaultItem>? = null

    // ------------------------------------------------------------------- the index

    fun items(): List<VaultItem> {
        cache?.let { return it.toList() }
        val loaded = if (!indexFile.exists()) {
            mutableListOf<VaultItem>()
        } else {
            VaultFileReader(FileSource(indexFile), indexKey).use { reader ->
                val bytes = ByteArrayOutputStream().also { reader.copyTo(it) }.toByteArray()
                VaultIndex.parse(bytes).toMutableList()
            }
        }
        cache = loaded
        return loaded.toList()
    }

    private fun writeIndex(items: List<VaultItem>) {
        // Written beside the real file and moved into place, so an interrupted write
        // leaves the previous index intact rather than a half-written one. Losing the
        // index loses every file key, which is the same as losing the vault.
        val temp = File(directory, "index.new")
        VaultFileWriter(temp, indexKey).use { it.write(VaultIndex.serialise(items)) }
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
        cache = items.toMutableList()
    }

    // -------------------------------------------------------------------- contents

    fun importItem(
        name: String,
        mimeType: String,
        input: InputStream,
        capturedAt: Long = System.currentTimeMillis(),
        thumbnail: ByteArray? = null,
    ): VaultItem {
        val id = VaultIndex.newItemId()
        val fileKey = Crypto.randomKey()
        var size = 0L

        VaultFileWriter(contentFile(id), fileKey).use { writer ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                writer.write(buffer, 0, read)
                size += read
            }
        }

        if (thumbnail != null) {
            VaultFileWriter(thumbnailFile(id), Crypto.hkdf(fileKey, INFO_THUMB)).use {
                it.write(thumbnail)
            }
        }

        val item = VaultItem(
            id = id,
            name = name,
            mimeType = mimeType,
            size = size,
            addedAt = System.currentTimeMillis(),
            capturedAt = capturedAt,
            fileKey = fileKey,
            hasThumbnail = thumbnail != null,
        )
        writeIndex(items() + item)
        return item
    }

    /** Seekable, so video playback can scrub without decrypting what came before. */
    fun open(item: VaultItem): VaultFileReader =
        VaultFileReader(FileSource(contentFile(item.id)), item.fileKey)

    fun readThumbnail(item: VaultItem): ByteArray? {
        if (!item.hasThumbnail) return null
        val file = thumbnailFile(item.id)
        if (!file.exists()) return null
        return VaultFileReader(FileSource(file), Crypto.hkdf(item.fileKey, INFO_THUMB)).use { reader ->
            ByteArrayOutputStream().also { reader.copyTo(it) }.toByteArray()
        }
    }

    fun delete(item: VaultItem) {
        contentFile(item.id).delete()
        thumbnailFile(item.id).delete()
        writeIndex(items().filterNot { it.id == item.id })
    }

    // --------------------------------------------------------------------- destroy

    /**
     * Delete everything in this vault, largest file first.
     *
     * By the time this runs the wrapped master key is already gone, so the content is
     * unrecoverable whether or not the deletion finishes. What this is actually for is
     * the number Android shows under app storage: a vault that held 18 GB and a decoy
     * that holds 40 MB disagree visibly until the bytes are gone, and largest-first
     * makes that number fall fast.
     *
     * Overwriting before deleting is not attempted. On flash storage it does not do what
     * it appears to do, and pretending otherwise would be worse than not claiming it.
     */
    fun destroyContents() {
        val files = itemsDir.listFiles()?.sortedByDescending { it.length() } ?: emptyList()
        for (file in files) file.delete()
        indexFile.delete()
        File(directory, "index.new").delete()
        cache = mutableListOf()
    }

    fun sizeOnDisk(): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * The stored container for an item, byte for byte.
     *
     * A bundle copies these across rather than re-encrypting, so writing one costs a file
     * copy instead of a full pass of AES over every photo.
     */
    internal fun containerLength(item: VaultItem): Long = contentFile(item.id).length()

    internal fun copyContainerTo(item: VaultItem, sink: java.io.OutputStream): Long {
        var written = 0L
        contentFile(item.id).inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                written += read
            }
        }
        return written
    }

    /**
     * The master key, for wrapping under the biometric key and nothing else.
     *
     * It is here reluctantly. Every other operation goes through this class precisely so
     * the key stays put, and the fingerprint path is the one case that genuinely needs
     * the raw bytes, because the Android Keystore has to be handed something to seal.
     * Returns a copy so the caller can wipe it without emptying the vault's own.
     */
    internal fun masterKeyForBiometrics(): ByteArray = masterKey.copyOf()

    /**
     * Whether a candidate key is this vault's. Used to confirm a typed password without
     * the password ever being compared to a stored copy of itself, because there isn't
     * one — the only test available is whether it unwraps the same key.
     */
    internal fun matchesMasterKey(candidate: ByteArray): Boolean =
        Crypto.constantTimeEquals(masterKey, candidate)

    private fun contentFile(id: String) = File(itemsDir, "$id.slf")

    private fun thumbnailFile(id: String) = File(itemsDir, "$id.thb")

    override fun close() {
        cache = null
        Crypto.wipe(indexKey)
    }

    companion object {
        const val INFO_INDEX = "sonderlock:index:v1"
        const val INFO_THUMB = "sonderlock:thumb:v1"

        /** Read a container straight from memory, for bundles and tests. */
        fun readerFor(bytes: ByteArray, key: ByteArray) = VaultFileReader(ByteArraySource(bytes), key)
    }
}
