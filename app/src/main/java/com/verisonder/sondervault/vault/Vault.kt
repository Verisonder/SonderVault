package com.verisonder.sondervault.vault

import com.verisonder.sondervault.crypto.ByteArraySource
import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.crypto.FileSource
import com.verisonder.sondervault.crypto.VaultFileReader
import com.verisonder.sondervault.crypto.VaultFileWriter
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
    /**
     * Whether this is the decoy.
     *
     * Carried on the vault itself rather than worked out by whoever is holding it. It was
     * previously the caller's opinion, and a screen that got it wrong rebuilt the slot
     * file around the wrong key and made a vault permanently unopenable. Anything that
     * can destroy data asks the vault, not the screen.
     */
    val isDecoy: Boolean,
) : Closeable {

    private val indexKey = Crypto.hkdf(masterKey, INFO_INDEX)
    private val itemsDir = File(directory, "items").apply { mkdirs() }
    private val indexFile = File(directory, "index")

    @Volatile
    private var cache: MutableList<VaultItem>? = null

    /**
     * Set by close, which zeroes the index key.
     *
     * Anything still running at that moment would otherwise derive its keys from an
     * all-zero array and write an index that the real key cannot read — losing every
     * file key in the vault. An import that is still finishing when the app goes to the
     * background is exactly that case. Failing here is recoverable; writing the index
     * under a dead key is not.
     */
    @Volatile
    private var closed = false

    private fun checkOpen() = check(!closed) { "the vault is closed" }

    // ------------------------------------------------------------------- the index

    /**
     * Every read and every write of the index goes through this lock.
     *
     * A screenful of tiles regenerating missing thumbnails runs one coroutine per tile,
     * and each one rewrites the whole index. Without the lock, two of them — or one of
     * them and an import — read the same list, and whichever writes second drops the
     * other's item silently.
     */
    @Synchronized
    fun items(): List<VaultItem> {
        // A read that lands just after locking would otherwise try to authenticate the
        // index against a zeroed key and throw. Nothing is lost by answering empty: the
        // screen asking is about to be replaced by the unlock screen. Writes still
        // refuse outright, because a write is the one that does damage.
        if (closed) return emptyList()
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

    /**
     * Read the index, change it, write it back, without anything getting in between.
     *
     * The lock covers only this — never a file copy — so importing a large video does
     * not block a grid full of tiles from recording the thumbnails they just made.
     */
    @Synchronized
    private fun mutateIndex(change: (List<VaultItem>) -> List<VaultItem>) {
        writeIndex(change(items()))
    }

    @Synchronized
    private fun writeIndex(items: List<VaultItem>) {
        checkOpen()
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
        checkOpen()
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
        mutateIndex { it + item }
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

    /**
     * Attach a thumbnail to an item that has none.
     *
     * Thumbnails are derived data, not content: they can be rebuilt from the item at any
     * time. Items restored from a bundle arrive without one, and a video frame can fail
     * to decode at import, so the grid regenerates on demand rather than leaving a grey
     * square forever.
     *
     * A screenful of tiles can discover they are missing thumbnails at the same moment,
     * and each one rewrites the index — so the record goes in through mutateIndex, which
     * is where that race is settled.
     */
    fun attachThumbnail(item: VaultItem, thumbnail: ByteArray): VaultItem {
        checkOpen()
        VaultFileWriter(thumbnailFile(item.id), Crypto.hkdf(item.fileKey, INFO_THUMB)).use {
            it.write(thumbnail)
        }
        val updated = item.copy(hasThumbnail = true)
        // Only the record for this item is touched. Anything else that changed while the
        // thumbnail was being made is left as it is rather than reverted to a stale copy.
        mutateIndex { current -> current.map { if (it.id == item.id) updated else it } }
        return updated
    }

    fun delete(item: VaultItem) {
        checkOpen()
        contentFile(item.id).delete()
        thumbnailFile(item.id).delete()
        mutateIndex { current -> current.filterNot { it.id == item.id } }
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
     * The master key, for re-wrapping it and nothing else — under the biometric key, or
     * under a new set of password slots.
     *
     * It is here reluctantly. Every other operation goes through this class precisely so
     * the key stays put. Returns a copy so the caller can wipe it without emptying the
     * vault's own.
     */
    internal fun masterKeyCopy(): ByteArray = masterKey.copyOf()

    /**
     * Whether a candidate key is this vault's. Used to confirm a typed password without
     * the password ever being compared to a stored copy of itself, because there isn't
     * one — the only test available is whether it unwraps the same key.
     */
    internal fun matchesMasterKey(candidate: ByteArray): Boolean =
        Crypto.constantTimeEquals(masterKey, candidate)

    private fun contentFile(id: String) = File(itemsDir, "$id.slf")

    private fun thumbnailFile(id: String) = File(itemsDir, "$id.thb")

    /**
     * Closed means the key material is gone, not merely that nobody is looking.
     *
     * The master key is zeroed here too. It was previously left in the heap for the
     * garbage collector to get to eventually, which meant locking the app did not
     * actually take the key out of memory — the one thing locking is for.
     */
    @Synchronized
    override fun close() {
        closed = true
        cache = null
        Crypto.wipe(indexKey, masterKey)
    }

    companion object {
        const val INFO_INDEX = "sondervault:index:v1"
        const val INFO_THUMB = "sondervault:thumb:v1"

        /** Read a container straight from memory, for bundles and tests. */
        fun readerFor(bytes: ByteArray, key: ByteArray) = VaultFileReader(ByteArraySource(bytes), key)
    }
}
