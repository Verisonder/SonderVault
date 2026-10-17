package com.verisonder.sondervault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.display
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.security.BiometricKey
import com.verisonder.sondervault.security.BiometricPrompts
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultStore
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
    activity: FragmentActivity,
    reason: String,
    confirmLabel: String,
    onConfirmed: () -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fingerprintReady = remember {
        BiometricKey.isAvailable(activity) && BiometricKey.isEnabled(activity.filesDir)
    }

    fun tryFingerprint() {
        wrong = false
        BiometricPrompts.unlock(activity, activity.filesDir) { key, _ ->
            // The fingerprint only ever unwraps the real vault's key, so this refuses
            // inside the decoy — which is right. Someone made to open the decoy should
            // not then be able to press a finger and take files out of it.
            if (key != null && vault.matchesMasterKey(key)) {
                Crypto.wipe(key)
                onConfirmed()
            } else {
                if (key != null) Crypto.wipe(key)
                wrong = true
            }
        }
    }

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
        containerColor = Palette.panel,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Enter your password", style = display(20), color = Palette.text) },
        text = {
            Column {
                Text(reason, color = Palette.dim, fontSize = 14.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(16.dp))
                InputField(
                    value = password,
                    onChange = { password = it; wrong = false },
                    placeholder = "Password",
                    password = true,
                    enabled = !checking,
                    problem = if (wrong) "That is not your password." else null,
                    imeAction = ImeAction.Go,
                    onAction = { submit() },
                    fill = Palette.card2,
                )
                if (fingerprintReady) {
                    Spacer(Modifier.height(4.dp))
                    TextAction("Use fingerprint", enabled = !checking) { tryFingerprint() }
                }
            }
        },
        confirmButton = {
            PillButton(
                confirmLabel,
                enabled = password.isNotEmpty() || checking,
                working = checking,
                modifier = Modifier.height(44.dp),
            ) { submit() }
        },
        dismissButton = {
            TextAction("Cancel", enabled = !checking, color = Palette.dim, onClick = onCancel)
        },
    )
}
