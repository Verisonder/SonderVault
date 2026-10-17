package com.verisonder.sondervault.vault

/**
 * Cleaning names and types that come from outside the app.
 *
 * A file's name is shown on screen and, when an item is put back on the phone, used as
 * the name of a real file. A bundle made by someone else can carry any name at all —
 * "../../Android/data/…" included — and on Android 9 putting such an item back wrote
 * straight to that path. Separators, control characters and leading dots are removed
 * here, and the length is capped.
 */
object SafeNames {

    private const val MAX_NAME = 180
    private val MIME = Regex("^[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*$")

    fun fileName(raw: String): String {
        val cleaned = buildString {
            for (c in raw) {
                when {
                    c == '/' || c == '\\' || c == ':' -> append('_')
                    c.isISOControl() -> Unit
                    // Bidirectional overrides can make "gpj.exe" display as "exe.jpg".
                    c in '‪'..'‮' || c in '⁦'..'⁩' -> Unit
                    else -> append(c)
                }
            }
        }.trim().trimStart('.').trim()
        val short = if (cleaned.length > MAX_NAME) {
            val ext = cleaned.substringAfterLast('.', "").take(12)
            val stem = cleaned.substringBeforeLast('.', cleaned).take(MAX_NAME - ext.length - 1)
            if (ext.isEmpty()) stem else "$stem.$ext"
        } else {
            cleaned
        }
        return short.ifEmpty { "file" }
    }

    fun mimeType(raw: String): String {
        val t = raw.trim().lowercase().substringBefore(';').trim()
        return if (MIME.matches(t)) t else "application/octet-stream"
    }
}
