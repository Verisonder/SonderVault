package com.verisonder.sondervault.bundle

import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.crypto.FileSource
import com.verisonder.sondervault.crypto.RecoveryPhrase
import com.verisonder.sondervault.crypto.VaultFileReader
import com.verisonder.sondervault.crypto.asInputStream
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Writes a bundle: a header, a sealed manifest, and every chosen item's container copied
 * across unchanged.
 *
 * Copying rather than re-encrypting is what makes exporting a large vault a file copy
 * instead of a second full pass of AES over every photo. The keys are already per-item,
 * so handing one over in the manifest hands over that item and nothing else.
 */
object BundleWriter {

    class Written(val entries: Int, val bytes: Long)

    fun write(
        vault: Vault,
        items: List<VaultItem>,
        phrase: List<String>,
        sink: OutputStream,
        memKiB: Int = Crypto.ARGON_MEM_KIB,
        iterations: Int = Crypto.ARGON_ITERS,
        parallelism: Int = Crypto.ARGON_PAR,
    ): Written {
        require(items.isNotEmpty()) { "nothing selected" }

        // Offsets are known before anything is written because a container's length is
        // just the file's length on disk.
        var running = 0L
        val entries = items.map { item ->
            val length = vault.containerLength(item)
            BundleEntry(
                name = item.name,
                mimeType = item.mimeType,
                size = item.size,
                capturedAt = item.capturedAt,
                fileKey = item.fileKey,
                offset = running,
                length = length,
            ).also { running += length }
        }

        val salt = Crypto.random(Bundle.SALT_BYTES)
        val nonce = Crypto.random(Crypto.GCM_NONCE_BYTES)
        val bundleKey = RecoveryPhrase.toKey(phrase, salt, memKiB, iterations, parallelism)

        val manifest = Bundle.serialise(entries)
        val sealedLength = manifest.size + Crypto.GCM_TAG_BYTES
        val header = Bundle.header(salt, nonce, sealedLength, memKiB, iterations, parallelism)
        val sealed = Crypto.gcmSeal(bundleKey, nonce, manifest, header)
        Crypto.wipe(bundleKey, manifest)

        var total = 0L
        BufferedOutputStream(sink).use { out ->
            out.write(header)
            out.write(sealed)
            total += header.size + sealed.size
            for (item in items) {
                total += vault.copyContainerTo(item, out)
            }
        }
        return Written(entries.size, total)
    }
}

/**
 * Reads a bundle back.
 *
 * Opening only needs the header and the manifest, so a wrong phrase is refused after one
 * key derivation rather than after reading gigabytes.
 */
class BundleReader private constructor(
    private val file: File,
    val entries: List<BundleEntry>,
    private val entriesStart: Long,
) {

    /**
     * Pull everything into a vault.
     *
     * Items are decrypted and written again under fresh keys rather than adopted as they
     * are. It costs a pass over the data and it is worth it: a bundle that leaks later,
     * with its phrase, then says nothing about the vault it was restored into.
     */
    fun extractInto(vault: Vault, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        var done = 0
        for (entry in entries) {
            RandomAccessFile(file, "r").use { raf ->
                val source = FileSource(raf, entriesStart + entry.offset, entry.length)
                VaultFileReader(source, entry.fileKey).use { reader ->
                    vault.importItem(
                        name = entry.name,
                        mimeType = entry.mimeType,
                        input = reader.asInputStream(),
                        capturedAt = entry.capturedAt,
                        thumbnail = null,
                    )
                }
            }
            done++
            onProgress(done, entries.size)
        }
        return done
    }

    /** A single item's plaintext, for previewing without importing. */
    fun open(entry: BundleEntry): VaultFileReader = VaultFileReader(
        FileSource(RandomAccessFile(file, "r"), entriesStart + entry.offset, entry.length),
        entry.fileKey,
    )

    companion object {

        class WrongPhrase : IOException("That code does not open this file")

        fun open(file: File, phrase: List<String>): BundleReader {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(Bundle.HEADER_SIZE)
                if (raf.length() < Bundle.HEADER_SIZE.toLong()) throw IOException("not a bundle")
                raf.readFully(header)
                if (!Crypto.constantTimeEquals(header.copyOf(8), Bundle.MAGIC)) {
                    throw IOException("not a bundle")
                }
                if (header[8].toInt() != Bundle.VERSION) throw IOException("unsupported version")

                val memKiB = Crypto.getIntBE(header, 9)
                val iterations = Crypto.getIntBE(header, 13)
                val parallelism = header[17].toInt() and 0xFF
                if (memKiB !in 8..1_048_576 || iterations !in 1..64 || parallelism !in 1..16) {
                    throw IOException("implausible parameters")
                }
                val salt = header.copyOfRange(18, 34)
                val nonce = header.copyOfRange(34, 46)
                val sealedLength = Crypto.getIntBE(header, 46)
                if (sealedLength <= Crypto.GCM_TAG_BYTES ||
                    sealedLength > 64 * 1024 * 1024 ||
                    raf.length() < Bundle.HEADER_SIZE + sealedLength
                ) {
                    throw IOException("bundle is damaged")
                }

                val sealed = ByteArray(sealedLength)
                raf.readFully(sealed)

                val key = RecoveryPhrase.toKey(phrase, salt, memKiB, iterations, parallelism)
                val manifest = try {
                    Crypto.gcmOpen(key, nonce, sealed, header)
                } finally {
                    Crypto.wipe(key)
                } ?: throw WrongPhrase()

                val entries = Bundle.parse(manifest)
                Crypto.wipe(manifest)

                val start = Bundle.HEADER_SIZE.toLong() + sealedLength
                val expected = entries.sumOf { it.length }
                if (raf.length() < start + expected) throw IOException("bundle is incomplete")

                return BundleReader(file, entries, start)
            }
        }
    }
}
