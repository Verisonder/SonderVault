package com.verisonder.sondervault.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.verisonder.sondervault.crypto.VaultFileReader
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem

/**
 * Lets the player read a video straight out of the vault, decrypting only the part it is
 * actually playing.
 *
 * This is what the container format was designed for. The player seeks constantly — to
 * read the moov atom, to scrub, to buffer ahead — and every seek arrives here as a
 * position. Because the file is AES-CTR in independently authenticated blocks, a seek
 * decrypts one block rather than everything before it.
 *
 * Nothing is ever written out. There is no temporary decrypted copy for the player to
 * read, which is the usual shortcut and the usual leak.
 */
@androidx.media3.common.util.UnstableApi
class VaultDataSource(
    private val vault: Vault,
    private val item: VaultItem,
) : BaseDataSource(true) {

    private var reader: VaultFileReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val opened = vault.open(item)
        reader = opened
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            opened.plainSize - position
        } else {
            dataSpec.length
        }
        if (remaining < 0) remaining = 0
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val active = reader ?: return C.RESULT_END_OF_INPUT

        val wanted = minOf(length.toLong(), remaining).toInt()
        val bytes = active.read(position, wanted)
        if (bytes.isEmpty()) return C.RESULT_END_OF_INPUT

        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        position += bytes.size
        remaining -= bytes.size
        bytesTransferred(bytes.size)
        return bytes.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        transferEnded()
    }

    /** One factory per item, since a factory knows which file it opens. */
    @androidx.media3.common.util.UnstableApi
    class Factory(private val vault: Vault, private val item: VaultItem) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(vault, item)
    }

    companion object {
        /**
         * The player needs a uri and never dereferences it, because the factory already
         * knows which file to open. It exists to give the media item an identity.
         */
        fun uriFor(item: VaultItem): Uri = Uri.parse("sondervault://item/${item.id}")
    }
}
