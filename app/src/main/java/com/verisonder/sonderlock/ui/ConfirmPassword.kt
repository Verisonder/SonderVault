package com.verisonder.sonderlock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks for the password again before anything leaves the vault.
 *
 * The vault being open is not the same as the owner being present. A phone handed over
 * unlocked, or picked up while the screen is on, is already past the lock screen — so
 * anything that writes a decrypted file back out into the world asks once more.
 *
 * Deleting does not go through this. Deleting destroys rather than exposes, and the
 * confirmation for it is about not doing it by accident.
 */
@Composable
fun ConfirmPassword(
    store: VaultStore,
    vault: Vault,
    reason: String,
    confirmLabel: String,
    onConfirmed: () -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (password.isEmpty() || checking) return
        checking = true
        wrong = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                store.confirms(password.toByteArray(Charsets.UTF_8), vault)
            }
            checking = false
            if (ok) {
                onConfirmed()
            } else {
                password = ""
                wrong = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!checking) onCancel() },
        title = { Text("Enter your password") },
        text = {
            Column {
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; wrong = false },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !checking,
                    isError = wrong,
                    supportingText = if (wrong) {
                        { Text("That is not your password.") }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = password.isNotEmpty() && !checking) {
                Text(if (checking) "Checking…" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !checking) { Text("Cancel") }
        },
    )
}
