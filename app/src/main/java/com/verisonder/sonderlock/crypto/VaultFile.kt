package com.verisonder.sonderlock.crypto

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * .slf containers. See docs/FORMAT.md section 3.
 *
 * AES-256-CTR so playback can seek, HMAC-SHA256 per block so integrity does not require
 * reading a 2 GB file before showing the first frame.
 */
object VaultFile {

    val MAGIC = byteArrayOf('S'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    const val VERSION = 1

    const val PREFIX_SIZE = 22                              // magic 4, version 1, log2 1, nonce 8, size 8
    const val HEADER_SIZE = PREFIX_SIZE + Crypto.MAC_BYTES  // 54
    const val DEFAULT_BLOCK_LOG2 = 20                       // 1 MiB

    const val INFO_ENC = "sonderlock:enc:v1"
    const val INFO_MAC = "sonderlock:mac:v1"

    fun encryptionKey(fileKey: ByteArray): ByteArray = Crypto.hkdf(fileKey, INFO_ENC)
    fun macKey(fileKey: ByteArray): ByteArray = Crypto.hkdf(fileKey, INFO_MAC)

    fun containerSize(plainSize: Long, blockLog2: Int = DEFAULT_BLOCK_LOG2): Long {
        val blockSize = 1L shl blockLog2
        val blocks = (plainSize + blockSize - 1) / blockSize
        return HEADER_SIZE + plainSize + blocks * Crypto.MAC_BYTES
    }

    internal fun blockMac(
        macKey: ByteArray,
        nonce: ByteArray,
        index: Long,
        ciphertext: ByteArray,
        length: Int,
    ): ByteArray = Crypto.hmac(
        macKey,
        nonce,
        Crypto.longBE(index),
        Crypto.longBE(length.toLong()),
        if (length == ciphertext.size) ciphertext else ciphertext.copyOf(length),
    )

    internal fun prefix(blockLog2: Int, nonce: ByteArray, plainSize: Long): ByteArray {
        val out = ByteArray(PREFIX_SIZE)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION.toByte()
        out[5] = blockLog2.toByte()
        System.arraycopy(nonce, 0, out, 6, 8)
        Crypto.putLongBE(out, 14, plainSize)
        return out
    }
}

/**
 * Anything the reader can seek within. A file on disk in production, a byte array in
 * tests, and later a bundle entry addressed at an offset inside a larger file.
 */
interface RandomSource : Closeable {
    val size: Long
    fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int
}

class FileSource(private val file: RandomAccessFile, private val base: Long = 0, override val size: Long = file.length() - base) : RandomSource {
    constructor(file: File) : this(RandomAccessFile(file, "r"))

    override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        file.seek(base + position)
        var read = 0
        while (read < length) {
            val n = file.read(destination, offset + read, length - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    override fun close() = file.close()
}

class ByteArraySource(private val bytes: ByteArray) : RandomSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), destination, offset, n)
        return n
    }

    override fun close() = Unit
}

/**
 * Writes a container to a file.
 *
 * The plaintext size lives in the authenticated header, at the front, and is not known
 * until the last byte has arrived — so the header is written as a placeholder and
 * rewritten at close. That is why this takes a real file rather than a stream. It is
 * always our own storage, never a caller's pipe, so seeking back is free.
 */
class VaultFileWriter(
    private val target: File,
    fileKey: ByteArray,
    private val blockLog2: Int = VaultFile.DEFAULT_BLOCK_LOG2,
) : Closeable {

    private val encKey = VaultFile.encryptionKey(fileKey)
    private val macKey = VaultFile.macKey(fileKey)
    private val nonce = Crypto.random(8)
    private val blockSize = 1 shl blockLog2
    private val countersPerBlock = (blockSize / 16).toLong()

    private val raf = RandomAccessFile(target, "rw")
    private val buffer = ByteArray(blockSize)
    private var buffered = 0
    private var blockIndex = 0L
    private var plainSize = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(ByteArray(VaultFile.HEADER_SIZE))   // placeholder, rewritten at close
    }

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        check(!closed) { "writer is closed" }
        var pos = offset
        var left = length
        while (left > 0) {
            val take = minOf(left, blockSize - buffered)
            System.arraycopy(data, pos, buffer, buffered, take)
            buffered += take
            pos += take
            left -= take
            plainSize += take
            if (buffered == blockSize) flushBlock()
        }
    }

    private fun flushBlock() {
        if (buffered == 0) return
        val plain = if (buffered == blockSize) buffer else buffer.copyOf(buffered)
        val ciphertext = Crypto.ctr(encKey, nonce, blockIndex * countersPerBlock, plain)
        raf.write(ciphertext, 0, buffered)
        raf.write(VaultFile.blockMac(macKey, nonce, blockIndex, ciphertext, buffered))
        blockIndex++
        buffered = 0
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushBlock()
            val prefix = VaultFile.prefix(blockLog2, nonce, plainSize)
            raf.seek(0)
            raf.write(prefix)
            raf.write(Crypto.hmac(macKey, prefix))
            raf.fd.sync()
        } finally {
            raf.close()
            Crypto.wipe(encKey, macKey, buffer)
        }
    }
}

/**
 * Reads a container from an arbitrary plaintext offset, touching only the blocks the
 * read actually covers. Damage in a block fails reads that touch it and nothing else.
 */
class VaultFileReader(private val source: RandomSource, fileKey: ByteArray) : Closeable {

    private val encKey = VaultFile.encryptionKey(fileKey)
    private val macKey = VaultFile.macKey(fileKey)

    val blockLog2: Int
    val blockSize: Int
    val plainSize: Long
    private val nonce: ByteArray
    private val countersPerBlock: Long

    init {
        val header = ByteArray(VaultFile.HEADER_SIZE)
        if (source.readAt(0, header, 0, header.size) != header.size) {
            throw IOException("truncated header")
        }
        val prefix = header.copyOf(VaultFile.PREFIX_SIZE)
        if (!Crypto.constantTimeEquals(prefix.copyOf(4), VaultFile.MAGIC)) {
            throw IOException("not a vault file")
        }
        if (prefix[4].toInt() != VaultFile.VERSION) throw IOException("unsupported version")
        if (!Crypto.constantTimeEquals(
                header.copyOfRange(VaultFile.PREFIX_SIZE, VaultFile.HEADER_SIZE),
                Crypto.hmac(macKey, prefix),
            )
        ) {
            throw IOException("header failed authentication")
        }
        blockLog2 = prefix[5].toInt() and 0xFF
        if (blockLog2 !in 8..26) throw IOException("implausible block size")
        blockSize = 1 shl blockLog2
        countersPerBlock = (blockSize / 16).toLong()
        nonce = prefix.copyOfRange(6, 14)
        plainSize = Crypto.getLongBE(prefix, 14)
        if (plainSize < 0) throw IOException("implausible size")
        if (source.size < VaultFile.containerSize(plainSize, blockLog2)) {
            throw IOException("file is shorter than its declared size")
        }
    }

    fun read(offset: Long, length: Int): ByteArray {
        val available = (plainSize - offset).coerceAtLeast(0)
        val want = minOf(length.toLong(), available).toInt()
        if (want <= 0) return ByteArray(0)
        val out = ByteArray(want)
        var written = 0
        forEachBlock(offset, want.toLong()) { plain, from, count ->
            System.arraycopy(plain, from, out, written, count)
            written += count
        }
        return out
    }

    fun copyTo(sink: OutputStream, offset: Long = 0, length: Long = plainSize - offset) {
        val available = (plainSize - offset).coerceAtLeast(0)
        val want = minOf(length, available)
        if (want <= 0) return
        forEachBlock(offset, want) { plain, from, count -> sink.write(plain, from, count) }
    }

    private inline fun forEachBlock(offset: Long, length: Long, consume: (ByteArray, Int, Int) -> Unit) {
        val first = offset / blockSize
        val last = (offset + length - 1) / blockSize
        var remaining = length
        var cursor = offset
        for (index in first..last) {
            val plain = decryptBlock(index)
            val within = (cursor - index * blockSize).toInt()
            val count = minOf(remaining, (plain.size - within).toLong()).toInt()
            consume(plain, within, count)
            cursor += count
            remaining -= count
            if (remaining <= 0) break
        }
    }

    private fun decryptBlock(index: Long): ByteArray {
        val stored = blockSize.toLong() + Crypto.MAC_BYTES
        val position = VaultFile.HEADER_SIZE + index * stored
        val ctLength = minOf(blockSize.toLong(), plainSize - index * blockSize).toInt()
        if (ctLength <= 0) throw IOException("block $index is past the end of the file")

        val buffer = ByteArray(ctLength + Crypto.MAC_BYTES)
        if (source.readAt(position, buffer, 0, buffer.size) != buffer.size) {
            throw IOException("block $index is truncated")
        }
        val ciphertext = buffer.copyOf(ctLength)
        val tag = buffer.copyOfRange(ctLength, buffer.size)
        if (!Crypto.constantTimeEquals(tag, VaultFile.blockMac(macKey, nonce, index, ciphertext, ctLength))) {
            throw IOException("block $index failed authentication")
        }
        return Crypto.ctr(encKey, nonce, index * countersPerBlock, ciphertext)
    }

    override fun close() {
        source.close()
        Crypto.wipe(encKey, macKey)
    }
}
