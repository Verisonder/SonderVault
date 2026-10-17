package com.verisonder.sondervault.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verisonder.sondervault.R
import com.verisonder.sondervault.crypto.RecoveryPhrase

@Composable
fun Centred(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/**
 * The wordlist, loaded once.
 *
 * Reached through R.raw rather than by name, so resource shrinking can see that it is
 * used. Looking it up with getIdentifier would let R8 strip the file and the app would
 * fail to generate a code, in release builds only.
 */
object Wordlist {
    @Volatile
    private var cached: RecoveryPhrase? = null

    fun of(context: Context): RecoveryPhrase = cached ?: synchronized(this) {
        cached ?: RecoveryPhrase.from(
            context.resources.openRawResource(R.raw.bip39_en).bufferedReader().lineSequence(),
        ).also { cached = it }
    }
}

/**
 * Copy a secret — a six-word code — so it can be pasted once, and not kept.
 *
 * Marked sensitive, so Android 13 and later do not show it in the paste preview and
 * keyboards with a clipboard history are told not to save it. Cleared after a minute if
 * it is still what is on the clipboard, so it does not sit there for the next app that
 * reads it.
 */
fun copySecret(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("code", text)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= 33) {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        } else {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val current = clipboard.primaryClip
            if (current != null && current.itemCount > 0 &&
                current.getItemAt(0).text?.toString() == text
            ) {
                clipboard.clearPrimaryClip()
            }
        }
    }, 60_000L)
}

/**
 * The password as bytes for exactly as long as [use] runs, then zeroed. The typed text
 * itself is a String the app cannot wipe, but this way no extra copy of it is left lying
 * in memory after each try.
 */
inline fun <T> withPasswordBytes(text: String, use: (ByteArray) -> T): T {
    val bytes = text.toByteArray(Charsets.UTF_8)
    try {
        return use(bytes)
    } finally {
        java.util.Arrays.fill(bytes, 0)
    }
}
