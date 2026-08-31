package com.verisonder.sondervault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MINIMUM_LENGTH = 8

/**
 * Setting the duress password.
 *
 * Two modes, and the difference matters enough to be a choice rather than a default:
 * one opens a separate set of photos and changes nothing, the other opens the same
 * separate set and destroys the real vault on the way. The first can be used more than
 * once; the second works exactly once and cannot be undone.
 *
 * Either way the next step is choosing what the second vault holds, because a second
 * password that opens an empty vault announces itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuressScreen(
    store: VaultStore,
    vault: Vault,
    onChooseDecoyPhotos: (Vault) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Nothing here is safe to run against the decoy. Setting a duress password rebuilds
    // the slot file around the vault it is given, so run from inside the decoy it writes
    // the decoy's key into the real slot and the real vault becomes unopenable. The store
    // refuses too; this is so the screen is never even shown.
    if (vault.isDecoy) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    // Set already means the current duress password identifies its own slot, so that is
    // the one to ask for. The main password is only needed to create the first one.
    val alreadySet = remember { store.hasSecondVault(vault) }
    var current by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var wipes by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val tooShort = duress.isNotEmpty() && duress.length < MINIMUM_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != duress
    val sameAsMain = duress.isNotEmpty() && duress == current
    val ready = current.isNotEmpty() &&
        duress.length >= MINIMUM_LENGTH &&
        confirm == duress &&
        !sameAsMain &&
        !working

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duress password") },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !working) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                if (alreadySet) {
                    "Change the password, or what happens when it is used."
                } else {
                    "A duress password that opens a different set of photos. Anyone made " +
                        "to hand over a password can hand over this one."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = current,
                onValueChange = { current = it; problem = null },
                label = {
                    Text(if (alreadySet) "Current duress password" else "Your password")
                },
                singleLine = true,
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = duress,
                onValueChange = { duress = it; problem = null },
                label = { Text(if (alreadySet) "New duress password" else "Duress password") },
                singleLine = true,
                enabled = !working,
                isError = tooShort || sameAsMain,
                supportingText = when {
                    sameAsMain -> {
                        { Text("It has to be different from the one above.") }
                    }
                    tooShort -> {
                        { Text("At least $MINIMUM_LENGTH characters") }
                    }
                    else -> null
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
                label = {
                    Text(if (alreadySet) "New duress password again" else "Duress password again")
                },
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

            Spacer(Modifier.height(24.dp))
            Text("When it is used", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Choice(
                selected = !wipes,
                title = "Open the other photos",
                detail = "Nothing is destroyed. Works every time it is used.",
                onSelect = { wipes = false },
            )
            Choice(
                selected = wipes,
                title = "Open them and erase the real vault",
                detail = "Everything you were hiding is gone, permanently and immediately.",
                onSelect = { wipes = true },
            )

            if (wipes) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        "Typing this password by mistake destroys everything. Make it " +
                            "nothing like your own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            problem?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    working = true
                    problem = null
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching {
                                if (alreadySet) {
                                    val ok = store.changeDuress(
                                        current.toByteArray(Charsets.UTF_8),
                                        duress.toByteArray(Charsets.UTF_8),
                                        wipes,
                                    )
                                    if (!ok) error("wrong") else null
                                } else {
                                    store.setDuress(
                                        current.toByteArray(Charsets.UTF_8),
                                        vault,
                                        duress.toByteArray(Charsets.UTF_8),
                                        wipes,
                                    ) ?: error("failed")
                                }
                            }
                        }
                        working = false
                        result
                            .onSuccess { decoy ->
                                // A vault that already has its photos does not need to be
                                // filled again.
                                if (decoy != null) onChooseDecoyPhotos(decoy) else onClose()
                            }
                            .onFailure {
                                problem = if (alreadySet) {
                                    "That is not your current duress password."
                                } else {
                                    "That is not your password."
                                }
                            }
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Continue")
                }
            }

            if (!alreadySet) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Next you choose which photos the duress password shows. Pick " +
                        "ordinary ones — an empty vault gives the game away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (alreadySet) {
                Spacer(Modifier.height(24.dp))
                TextButton(
                    onClick = {
                        working = true
                        problem = null
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) {
                                store.removeDuress(current.toByteArray(Charsets.UTF_8))
                            }
                            working = false
                            if (ok) onClose()
                            else problem = "That is not your current duress password."
                        }
                    },
                    enabled = current.isNotEmpty() && !working,
                ) { Text("Remove the duress password") }
                Text(
                    "The photos it shows are deleted with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose, enabled = !working) { Text("Cancel") }
        }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.height(0.dp))
        Column(modifier = Modifier.padding(start = 8.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
