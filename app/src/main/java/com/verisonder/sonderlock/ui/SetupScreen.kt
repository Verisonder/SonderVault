package com.verisonder.sonderlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MINIMUM_LENGTH = 8

/**
 * Setup asks for one password and nothing else.
 *
 * The duress password lives in settings rather than here, because most people setting
 * this up want to hide some photos and do not want to be walked through a threat model
 * first. Everyone who needs a duress password will go looking for one.
 */
@Composable
fun SetupScreen(store: VaultStore, onDone: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val tooShort = password.isNotEmpty() && password.length < MINIMUM_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password
    val ready = password.length >= MINIMUM_LENGTH && confirm == password && !working

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Choose a password", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "It is the only way in. It cannot be reset or recovered.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; problem = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !working,
                isError = tooShort,
                supportingText = if (tooShort) {
                    { Text("At least $MINIMUM_LENGTH characters") }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; problem = null },
                label = { Text("Password again") },
                singleLine = true,
                enabled = !working,
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text("These do not match") }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            problem?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    working = true
                    problem = null
                    scope.launch {
                        // Argon2id at 64 MiB takes a moment on a phone and must not run
                        // on the thread drawing the screen.
                        val result = withContext(Dispatchers.Default) {
                            runCatching {
                                val bytes = password.toByteArray(Charsets.UTF_8)
                                val configured = store.configure(bytes, null, false)
                                VaultStore.Opened(configured.real, isDecoy = false, wiped = false)
                            }
                        }
                        working = false
                        result
                            .onSuccess { VaultSession.open(it); onDone() }
                            .onFailure { problem = it.message ?: "Could not create the vault" }
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Create vault")
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Uninstalling the app deletes everything in it. You can export a backup later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
