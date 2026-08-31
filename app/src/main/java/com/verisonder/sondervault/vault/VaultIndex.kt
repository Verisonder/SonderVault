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

    fun serialise(items: List<VaultItem>): ByteArray = buildString {
        appendLine(MAGIC)
        for (item in items) {
            append(item.id); append('\t')
            append(escape(item.name)); append('\t')
            append(escape(item.mimeType)); append('\t')
            append(item.size); append('\t')
            append(item.addedAt); append('\t')
            append(item.capturedAt); append('\t')
            append(hex(item.fileKey)); append('\t')
            append(if (item.hasThumbnail) '1' else '0')
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    fun parse(bytes: ByteArray): List<VaultItem> {
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        require(lines.isNotEmpty() && lines[0] == MAGIC) { "not a vault index" }
        val out = ArrayList<VaultItem>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            require(f.size == 8) { "index record has ${f.size} fields, expected 8" }
            out.add(
                VaultItem(
                    id = f[0],
                    name = unescape(f[1]),
                    mimeType = unescape(f[2]),
                    size = f[3].toLong(),
                    addedAt = f[4].toLong(),
                    capturedAt = f[5].toLong(),
                    fileKey = unhex(f[6]),
                    hasThumbnail = f[7] == "1",
                )
            )
        }
        return out
    }

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

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    fun newItemId(): String = hex(Crypto.random(16))
}
