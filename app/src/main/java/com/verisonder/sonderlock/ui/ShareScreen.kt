package com.verisonder.sonderlock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verisonder.sonderlock.bundle.Bundle
import com.verisonder.sonderlock.bundle.BundleWriter
import com.verisonder.sonderlock.crypto.RecoveryPhrase
import androidx.fragment.app.FragmentActivity
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turning a selection into one encrypted file with its own code.
 *
 * Sharing and backing up are the same operation and the same format, so they are one
 * screen: a backup is a share of everything, to yourself. What differs is only the wording
 * and how much the code matters.
 *
 * Choosing what goes in happens on the grid, where the photos are. This screen never had
 * any business asking again.
 *
 * The code is generated here and shown once. It is not stored anywhere — there is nothing
 * to look it up in later, which is the point and also the risk, so the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    store: VaultStore,
    vault: Vault,
    activity: FragmentActivity,
    itemIds: List<String>,
    isBackup: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen = remember(itemIds) { vault.items().filter { it.id in itemIds.toSet() } }

    var phrase by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmingPassword by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val fileName = if (isBackup) "sonderlock-backup-$stamp" else "sonderlock-$stamp"

    val saveCode = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(
                        RecoveryPhrase
                            .saveableText(phrase, "$fileName.${Bundle.EXTENSION}", isBackup)
                            .toByteArray(),
                    )
                }
            }
        }
    }

    val saveBundle = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            working = null
            return@rememberLauncherForActivityResult
        }
        working = "Writing"
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        BundleWriter.write(vault, chosen, phrase, out)
                    } ?: error("could not write there")
                }.exceptionOrNull()?.message
            }
            working = null
            problem = error
            finished = error == null
        }
    }

    fun begin() {
        // Generated fresh for every export. A code that leaks costs this file and nothing
        // else, and there is no single code that would open everything ever shared.
        phrase = Wordlist.of(context).generate()
        VaultSession.expectingExternalActivity = true
        saveBundle.launch("$fileName.${Bundle.EXTENSION}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            finished -> "Write this down"
                            isBackup -> "Back up everything"
                            chosen.size == 1 -> "Share 1 item"
                            else -> "Share ${chosen.size} items"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = working == null) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                working != null -> Centred {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(working!!, style = MaterialTheme.typography.bodyMedium)
                }

                finished -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text(
                        if (isBackup) {
                            "These six words are the only way back into this backup. " +
                                "Nothing else can open it and there is no reset."
                        } else {
                            "Whoever you send the file to needs these six words to open it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    CodeCard(phrase, onSaveToFile = {
                        VaultSession.expectingExternalActivity = true
                        saveCode.launch("$fileName-code.txt")
                    })
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }

                problem != null -> Centred {
                    Text(
                        problem!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { problem = null }) { Text("Try again") }
                }

                chosen.isEmpty() -> Centred {
                    Text("Nothing to share", style = MaterialTheme.typography.titleMedium)
                }

                else -> Centred {
                    Text(
                        if (chosen.size == 1) "1 item" else "${chosen.size} items",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "They go into one encrypted file with its own six word code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { confirmingPassword = true }) {
                        Text(if (isBackup) "Back up" else "Create file")
                    }
                }
            }
        }
    }

    if (confirmingPassword) {
        ConfirmPassword(
            store = store,
            vault = vault,
            activity = activity,
            reason = "This writes an encrypted copy out of the vault.",
            confirmLabel = "Continue",
            onConfirmed = { confirmingPassword = false; begin() },
            onCancel = { confirmingPassword = false },
        )
    }
}
