package com.verisonder.sonderlock.vault

/**
 * What an item is, decided from its media type.
 *
 * Everything that is not a photo or a video is a file. That includes the odd corners —
 * an audio recording, an archive — and lumping them in is deliberate: a fourth category
 * with one item in it is a worse filter than three that are always meaningful.
 */
enum class ItemKind { IMAGE, VIDEO, FILE;

    companion object {
        fun of(mimeType: String): ItemKind = when {
            mimeType.startsWith("image/") -> IMAGE
            mimeType.startsWith("video/") -> VIDEO
            else -> FILE
        }
    }
}

/** What the grid is showing. ALL is the default and the one people leave it on. */
enum class VaultFilter(val label: String) {
    ALL("All"),
    IMAGES("Photos"),
    VIDEOS("Videos"),
    FILES("Files");

    fun accepts(item: VaultItem): Boolean = when (this) {
        ALL -> true
        IMAGES -> ItemKind.of(item.mimeType) == ItemKind.IMAGE
        VIDEOS -> ItemKind.of(item.mimeType) == ItemKind.VIDEO
        FILES -> ItemKind.of(item.mimeType) == ItemKind.FILE
    }
}
