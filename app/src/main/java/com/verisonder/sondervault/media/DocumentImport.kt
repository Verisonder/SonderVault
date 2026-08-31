package com.verisonder.sondervault.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import com.verisonder.sondervault.vault.Vault

/**
 * Bringing in things that are not photos or videos: documents, archives, anything the
 * file picker will hand over.
 *
 * These do not live in MediaStore, so none of the media permissions apply and none are
 * asked for. The user picks each file explicitly and that grant is the only access the
 * app ever gets.
 */
object DocumentImport {

    class Outcome(val imported: Int, val failed: Int, val notRemoved: Int)

    /**
     * Asks for write access as well as read.
     *
     * The stock OpenMultipleDocuments contract requests read only, and deleting the
     * original then fails with a security exception no matter what the provider supports.
     * Asking for write is what makes removing it possible at all — though plenty of
     * providers still refuse, which is why the result counts what could not be removed
     * rather than assuming.
     */
    class PickWritable : ActivityResultContract<Array<String>, List<Uri>>() {

        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, input)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
            intent.clipData?.let { clip ->
                return (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            }
            return listOfNotNull(intent.data)
        }
    }

    fun importAll(context: Context, vault: Vault, uris: List<Uri>): Outcome {
        var imported = 0
        var failed = 0
        var notRemoved = 0

        for (uri in uris) {
            val details = describe(context, uri)
            val ok = runCatching {
                context.contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "could not read ${details.first}" }
                    vault.importItem(
                        name = details.first,
                        mimeType = details.second,
                        input = stream,
                        // Documents carry no capture date, so the file's own time is the
                        // only ordering there is.
                        capturedAt = System.currentTimeMillis(),
                        thumbnail = null,
                    )
                }
            }.isSuccess

            if (!ok) {
                failed++
                continue
            }
            imported++

            val removed = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
            if (!removed) notRemoved++
        }
        return Outcome(imported, failed, notRemoved)
    }

    private fun describe(context: Context, uri: Uri): Pair<String, String> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameColumn)?.let { name = it }
            }
        }
        val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return name to type
    }
}
