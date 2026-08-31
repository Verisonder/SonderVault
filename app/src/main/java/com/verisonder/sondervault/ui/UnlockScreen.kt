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
        working = true
        problem = null
        scope.launch {
            val opened = withContext(Dispatchers.Default) {
                runCatching { store.unlock(password.toByteArray(Charsets.UTF_8)) }.getOrNull()
            }
            working = false
            if (opened == null) {
                password = ""
                problem = "That did not open anything."
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
                enabled = !working,
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
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
                enabled = password.isNotEmpty() && !working,
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
