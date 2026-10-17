package com.verisonder.sondervault.vault

import com.verisonder.sondervault.crypto.Crypto

/**
 * One record per item held in a vault.
 *
 * The file key lives here rather than beside the content. The index is encrypted under a
 * key derived from the master key, so an index that cannot be read yields no file keys,
 * and the item blobs on disk are unattributable to anything without it.
 */
data class VaultItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val addedAt: Long,
    val capturedAt: Long,
    val fileKey: ByteArray,
    val hasThumbnail: Boolean,
) {
    // data class equality on a ByteArray compares references, which is wrong here and
    // silently so. Both are spelled out rather than left to the generated versions.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItem) return false
        return id == other.id && name == other.name && mimeType == other.mimeType &&
            size == other.size && addedAt == other.addedAt && capturedAt == other.capturedAt &&
            hasThumbnail == other.hasThumbnail && fileKey.contentEquals(other.fileKey)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + addedAt.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + hasThumbnail.hashCode()
        result = 31 * result + fileKey.contentHashCode()
        return result
    }
}

/**
 * The whole index, serialised as one record per line with tab-separated fields.
 *
 * Deliberately not JSON. Android's org.json is stubbed in unit tests, kotlinx
 * serialization is a dependency and a plugin, and the shape here is eight known fields
 * that will never nest. A format small enough to read in one sitting is worth more than
 * a familiar one, and the only free text is the filename, which is escaped.
 */
object VaultIndex {

    const val MAGIC = "SVIX1"

    /**
     * Written as bytes, with the keys turned into hex straight into the buffer, so no key
     * ever exists as a String (Strings cannot be wiped). The caller zeroes the result
     * once it has been encrypted. The format is unchanged.
     */
    fun serialise(items: List<VaultItem>): ByteArray {
        val out = WipeableBuffer()
        try {
            fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
            text(MAGIC); out.write('\n'.code)
            for (item in items) {
                text(item.id); out.write('\t'.code)
                text(escape(item.name)); out.write('\t'.code)
                text(escape(item.mimeType)); out.write('\t'.code)
                text(item.size.toString()); out.write('\t'.code)
                text(item.addedAt.toString()); out.write('\t'.code)
                text(item.capturedAt.toString()); out.write('\t'.code)
                for (byte in item.fileKey) {
                    val v = byte.toInt() and 0xFF
                    out.write(HEX[v ushr 4].code)
                    out.write(HEX[v and 0x0F].code)
                }
                out.write('\t'.code)
                out.write(if (item.hasThumbnail) '1'.code else '0'.code)
                out.write('\n'.code)
            }
            return out.toByteArray()
        } finally {
            out.wipe()
        }
    }

    fun parse(bytes: ByteArray): List<VaultItem> = parse(bytes, bytes.size)

    /**
     * Reads the first [length] bytes. Split on bytes rather than decoded to one big String
     * first, so the keys go from bytes to bytes and never become text. Safe for UTF-8:
     * tab and newline never occur inside a multi-byte character.
     */
    fun parse(bytes: ByteArray, length: Int): List<VaultItem> {
        val out = ArrayList<VaultItem>()
        var lineStart = 0
        var first = true
        while (lineStart <= length) {
            var lineEnd = lineStart
            while (lineEnd < length && bytes[lineEnd] != '\n'.code.toByte()) lineEnd++
            if (first) {
                val magic = String(bytes, lineStart, lineEnd - lineStart, Charsets.UTF_8)
                require(magic == MAGIC) { "not a vault index" }
                first = false
            } else if (!isBlank(bytes, lineStart, lineEnd)) {
                // Field boundaries within the line.
                val starts = ArrayList<Int>(9)
                val ends = ArrayList<Int>(9)
                var fieldStart = lineStart
                for (i in lineStart..lineEnd) {
                    if (i == lineEnd || bytes[i] == '\t'.code.toByte()) {
                        starts.add(fieldStart); ends.add(i); fieldStart = i + 1
                    }
                }
                // At least eight, rather than exactly eight. A later version that adds a
                // ninth field would otherwise make every index it has ever touched
                // unreadable to this one — and the index holds every file key in the
                // vault, so that is the whole vault, not one feature. Unknown trailing
                // fields are ignored on purpose.
                require(starts.size >= 8) { "index record has ${starts.size} fields, expected at least 8" }
                fun field(n: Int) = String(bytes, starts[n], ends[n] - starts[n], Charsets.UTF_8)
                out.add(
                    VaultItem(
                        id = field(0),
                        name = unescape(field(1)),
                        mimeType = unescape(field(2)),
                        size = field(3).toLong(),
                        addedAt = field(4).toLong(),
                        capturedAt = field(5).toLong(),
                        fileKey = unhex(bytes, starts[6], ends[6]),
                        hasThumbnail = field(7) == "1",
                    )
                )
            }
            lineStart = lineEnd + 1
        }
        require(!first) { "not a vault index" }
        return out
    }

    private fun isBlank(bytes: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (!Character.isWhitespace(bytes[i].toInt().toChar())) return false
        return true
    }

    private const val HEX = "0123456789abcdef"

    // A filename can hold anything a user can type, including the separator and a
    // newline. Escaping the three characters that would break the format is cheaper and
    // more obvious than quoting the whole field.
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

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

    /** Hex straight from the index bytes into a key, with no String in between. */
    private fun unhex(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val n = to - from
        require(n % 2 == 0) { "hex field has an odd length" }
        return ByteArray(n / 2) {
            val high = Character.digit(bytes[from + it * 2].toInt().toChar(), 16)
            val low = Character.digit(bytes[from + it * 2 + 1].toInt().toChar(), 16)
            require(high >= 0 && low >= 0) { "hex field holds a character that is not hex" }
            ((high shl 4) or low).toByte()
        }
    }

    /**
     * Character.digit answers -1 for anything that is not a hex digit, and the shift
     * turns that into an ordinary-looking byte. A single stray character therefore used
     * to produce a plausible key that simply did not open the file, with nothing said.
     * A key that is wrong is worth an exception; a key that is quietly wrong is not.
     */
    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex field has an odd length" }
        return ByteArray(s.length / 2) {
            val high = Character.digit(s[it * 2], 16)
            val low = Character.digit(s[it * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "hex field holds a character that is not hex" }
            ((high shl 4) or low).toByte()
        }
    }

    fun newItemId(): String = hex(Crypto.random(16))
}
