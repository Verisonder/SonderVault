package com.verisonder.sondervault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.security.BiometricKey
import com.verisonder.sondervault.security.BiometricPrompts
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * No app name, no logo, no heading.
 *
 * A lock screen that announces what it guards tells anyone glancing at the phone that
 * there is something here worth taking.
 *
 * A password that opens nothing gets the same flat answer whichever password it was, so
 * nothing here says whether a second one exists.
 */
@Composable
fun UnlockScreen(
    store: VaultStore,
    activity: FragmentActivity,
    onOpened: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val baseDir = activity.filesDir

    // Re-read on every return to the foreground, because the wait carries on while the
    // app is closed and the screen would otherwise come back showing a stale number.
    var waitMs by remember(VaultSession.foregroundCount) {
        mutableStateOf(store.remainingLockoutMs())
    }
    val waiting = waitMs > 0L

    // Ticks only while there is something to count down, so nothing runs in the ordinary
    // case. Re-reads the store rather than subtracting locally, so the number on screen
    // is the one the unlock will actually enforce.
    LaunchedEffect(waiting, VaultSession.foregroundCount) {
        while (waitMs > 0L) {
            kotlinx.coroutines.delay(500)
            waitMs = store.remainingLockoutMs()
        }
    }

    val fingerprintReady = remember {
        BiometricKey.isAvailable(activity) && BiometricKey.isEnabled(baseDir)
    }

    fun openWith(masterKey: ByteArray) {
        VaultSession.open(store.openWithMasterKey(masterKey))
        onOpened()
    }

    fun tryFingerprint() {
        problem = null
        BiometricPrompts.unlock(activity, baseDir) { key, message ->
            if (key != null) openWith(key) else problem = message
        }
    }

    fun submit() {
        if (password.isEmpty() || working) return
        // The store refuses anyway; this is so the button does not appear to do nothing.
        if (store.remainingLockoutMs() > 0L) {
            waitMs = store.remainingLockoutMs()
            return
        }
        working = true
        problem = null
        scope.launch {
            val opened = withContext(Dispatchers.Default) {
                runCatching { store.unlock(password.toByteArray(Charsets.UTF_8)) }.getOrNull()
            }
            working = false
            if (opened == null) {
                password = ""
                // Read the wait back rather than working it out here. The schedule lives
                // in one place and this screen does not get an opinion about it.
                waitMs = store.remainingLockoutMs()
                problem = if (waitMs > 0L) null else "That did not open anything."
            } else {
                VaultSession.open(opened)
                onOpened()
            }
        }
    }

    // The fingerprint prompt comes up on its own, because reaching for it deliberately is
    // a step nobody wants every single time.
    LaunchedEffect(fingerprintReady) {
        if (fingerprintReady) tryFingerprint()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; problem = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !working && !waiting,
                isError = problem != null || waiting,
                supportingText = when {
                    // Says how long, and nothing else. Not how many attempts have been
                    // made, not how many are left, not whether any of them were close.
                    waiting -> { { Text("Too many attempts. Try again in ${countdown(waitMs)}.") } }
                    problem != null -> { { Text(problem!!) } }
                    else -> null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { submit() },
                enabled = password.isNotEmpty() && !working && !waiting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Open")
                }
            }

            if (fingerprintReady) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { tryFingerprint() }) { Text("Use fingerprint") }
            }
        }
    }
}

/**
 * "2:34" once there is a minute or more to wait, "12 seconds" below that.
 *
 * Reads as a wait rather than as a stopwatch: someone who mistyped their password twice
 * should see something that sounds like a pause, not a penalty being served.
 */
private fun countdown(remainingMs: Long): String {
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(1)
    if (seconds < 60) return if (seconds == 1L) "1 second" else "$seconds seconds"
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}
