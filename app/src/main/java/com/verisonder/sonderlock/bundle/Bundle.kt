package com.verisonder.sonderlock.bundle

import com.verisonder.sonderlock.crypto.Crypto

/**
 * One entry inside a .sonderlock bundle.
 *
 * `offset` is measured from the start of the entries region rather than from the start of
 * the file. It has to be: the manifest sits before the entries and its own length depends
 * on the offsets it contains, so absolute positions would be circular.
 */
class BundleEntry(
    val name: String,
    val mimeType: String,
    val size: Long,
    val capturedAt: Long,
    val fileKey: ByteArray,
    val offset: Long,
    val length: Long,
)

/**
 * The shared and backed-up file format. See docs/FORMAT.md section 4.
 *
 * ```
 *  0   8  magic "SLBUNDL1"
 *  8   1  version
 *  9   4  Argon2 memory, KiB
 * 13   4  Argon2 iterations
 * 17   1  Argon2 parallelism
 * 18  16  salt
 * 34  12  GCM nonce
 * 46   4  sealed manifest length
 * 50   -  sealed manifest, with bytes 0..49 as associated data
 *  -   -  entries, each a complete .slf container
 * ```
 *
 * Everything before the sealed manifest is authenticated, including the manifest's own
 * length and the Argon2 parameters — so an attacker cannot quietly rewrite the cost down
 * to something searchable.
 */
object Bundle {

    val MAGIC = byteArrayOf(
        'S'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'U'.code.toByte(),
        'N'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte(),
    )
    const val VERSION = 1
    const val SALT_BYTES = 16
    const val HEADER_SIZE = 50
    const val EXTENSION = "sonderlock"

    /** Split above this. Most SD cards are FAT32, which cannot hold a file over 4 GB. */
    const val MAX_PART_BYTES = 3_500_000_000L

    const val MANIFEST_MAGIC = "SLBM1"

    fun header(
        salt: ByteArray,
        nonce: ByteArray,
        sealedLength: Int,
        memKiB: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, out, 0, 8)
        out[8] = VERSION.toByte()
        Crypto.putIntBE(out, 9, memKiB)
        Crypto.putIntBE(out, 13, iterations)
        out[17] = parallelism.toByte()
        System.arraycopy(salt, 0, out, 18, SALT_BYTES)
        System.arraycopy(nonce, 0, out, 34, Crypto.GCM_NONCE_BYTES)
        Crypto.putIntBE(out, 46, sealedLength)
        return out
    }

    // ---------------------------------------------------------------- the manifest

    fun serialise(entries: List<BundleEntry>): ByteArray = buildString {
        appendLine(MANIFEST_MAGIC)
        for (e in entries) {
            append(escape(e.name)); append('\t')
            append(escape(e.mimeType)); append('\t')
            append(e.size); append('\t')
            append(e.capturedAt); append('\t')
            append(hex(e.fileKey)); append('\t')
            append(e.offset); append('\t')
            append(e.length)
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    fun parse(bytes: ByteArray): List<BundleEntry> {
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        require(lines.isNotEmpty() && lines[0] == MANIFEST_MAGIC) { "not a bundle manifest" }
        val out = ArrayList<BundleEntry>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            require(f.size == 7) { "manifest record has ${f.size} fields, expected 7" }
            out.add(
                BundleEntry(
                    name = unescape(f[0]),
                    mimeType = unescape(f[1]),
                    size = f[2].toLong(),
                    capturedAt = f[3].toLong(),
                    fileKey = unhex(f[4]),
                    offset = f[5].toLong(),
                    length = f[6].toLong(),
                )
            )
        }
        return out
    }

    private fun escape(s: String) =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { out.append('\t'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }
}
