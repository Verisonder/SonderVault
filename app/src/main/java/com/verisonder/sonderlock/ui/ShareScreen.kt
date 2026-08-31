package com.verisonder.sonderlock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verisonder.sonderlock.bundle.BundleWriter
import com.verisonder.sonderlock.crypto.RecoveryPhrase
import com.verisonder.sonderlock.vault.Vault
import com.verisonder.sonderlock.vault.VaultItem
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export, whether it is one photo to send to someone or the whole vault as a backup.
 *
 * The two are the same operation and the same file format, so they are one screen. A
 * backup is a share of everything, to yourself.
 *
 * The code is generated here and shown once. It is not stored anywhere — there is nothing
 * to look it up in later, which is the point and also the risk, so the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    store: VaultStore,
    vault: Vault,
    everything: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember(vault, VaultSession.contentsChanged) { vault.items() }
    val selected = remember { mutableListOf<String>().toMutableStateList() }
    val grid = rememberLazyGridState()

    var phrase by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmingPassword by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    val stamp = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val fileName = if (everything) "sonderlock-backup-$stamp" else "sonderlock-$stamp"

    val saveCode = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(
                        RecoveryPhrase
                            .saveableText(phrase, "$fileName.sonderlock", everything)
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
        val chosen = if (everything) items else items.filter { it.id in selected }
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
        saveBundle.launch("$fileName.${com.verisonder.sonderlock.bundle.Bundle.EXTENSION}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            finished -> "Write this down"
                            everything -> "Back up everything"
                            selected.isEmpty() -> "Share"
                            else -> "${selected.size} selected"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = working == null) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (!everything && !finished && items.isNotEmpty() && working == null) {
                        TextButton(onClick = {
                            if (selected.size == items.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(items.map { it.id })
                            }
                        }) {
                            Text(if (selected.size == items.size) "None" else "All")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!finished && working == null) {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            onClick = { confirmingPassword = true },
                            enabled = everything || selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (everything) "Back up ${items.size} items" else "Create file")
                        }
                    }
                }
            }
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
                        if (everything) {
                            "These six words are the only way back into this backup. " +
                                "Nothing else can open it and there is no reset."
                        } else {
                            "Whoever you send the file to needs these six words to open it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    CodeCard(phrase, onSaveToFile = { saveCode.launch("$fileName-code.txt") })
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }

                problem != null -> Centred {
                    Text(problem!!, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { problem = null }) { Text("Try again") }
                }

                everything -> Centred {
                    Text("${items.size} items", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Everything in the vault goes into one encrypted file with its own code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items.isEmpty() -> Centred {
                    Text("Nothing to share", style = MaterialTheme.typography.titleMedium)
                }

                else -> LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.dragToSelect(
                        state = grid,
                        onStart = { index ->
                            items.getOrNull(index)?.id?.let { if (it !in selected) selected.add(it) }
                        },
                        onOver = { index ->
                            items.getOrNull(index)?.id?.let { if (it !in selected) selected.add(it) }
                        },
                        onFinish = {},
                    ),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                        ShareTile(vault, item, item.id in selected) {
                            if (item.id in selected) selected.remove(item.id)
                            else selected.add(item.id)
                        }
                    }
                }
            }
        }
    }

    if (confirmingPassword) {
        ConfirmPassword(
            store = store,
            vault = vault,
            reason = "This writes an encrypted copy out of the vault.",
            confirmLabel = "Continue",
            onConfirmed = { confirmingPassword = false; begin() },
            onCancel = { confirmingPassword = false },
        )
    }
}

@Composable
private fun ShareTile(vault: Vault, item: VaultItem, isSelected: Boolean, onToggle: () -> Unit) {
    val thumbnail by rememberVaultThumbnail(vault, item)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}
