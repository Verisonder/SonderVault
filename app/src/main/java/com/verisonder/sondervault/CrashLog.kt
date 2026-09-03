package com.verisonder.sondervault

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the last uncaught exception to a file so it can be read after the fact.
 *
 * This app is developed against a real device with no debugger attached and no way to
 * pull logcat, so a crash otherwise arrives as "it crashed" and gets diagnosed by
 * guesswork. One file in private storage turns that into a stack trace.
 *
 * It holds a stack trace and nothing else: no filenames from the vault, no keys, nothing
 * about what is stored. Deleted as soon as it has been read.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(context).writeText("$when_ on ${thread.name}\n\n${redact(stack.toString())}")
            }
            // Always hand back to the platform: swallowing this would leave the app in a
            // half-dead state rather than closing, which is worse than crashing.
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = file(context)
        return if (file.exists()) file.readText() else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * The file was documented as holding no filenames from the vault, and mostly it did
     * — the stored items are named after random ids. But an exception thrown while
     * importing carries the source uri in its message, and that uri holds the original
     * name of whatever was being hidden. Written here, that name ends up in plain text
     * on the device, which is the exact thing the import was for.
     *
     * The scheme is kept because it is what makes the trace worth reading; everything
     * after it goes.
     */
    private fun redact(text: String): String = text
        .replace(Regex("""(content|file|document)://\S*""", RegexOption.IGNORE_CASE), "$1://…")
        .replace(Regex("""/storage/\S*""", RegexOption.IGNORE_CASE), "/storage/…")
}
