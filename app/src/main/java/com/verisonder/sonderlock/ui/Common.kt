package com.verisonder.sonderlock.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verisonder.sonderlock.R
import com.verisonder.sonderlock.crypto.RecoveryPhrase

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
