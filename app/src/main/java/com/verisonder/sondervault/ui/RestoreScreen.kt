package com.verisonder.sondervault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.bundle.BundleReader
import com.verisonder.sondervault.vault.Vault
import androidx.compose.ui.res.painterResource
import com.verisonder.sondervault.R
import com.verisonder.sondervault.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opening a bundle: one someone sent, or your own backup.
 *
 * The file is copied into private storage before it is read. Reading a bundle needs to
 * jump around inside it, and a document picked through the storage access framework
 * cannot always be seeked. The copy is deleted as soon as the import finishes, whether it
 * worked or not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(vault: Vault, activity: FragmentActivity, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var staged by remember { mutableStateOf<File?>(null) }
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<Int?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        VaultSession.externalActivityFinished()
        if (uri == null) {
            onDone()
            return@rememberLauncherForActivityResult
        }
        working = "Reading the file"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "incoming.sondervault")
                    context.contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "could not read that file" }
                        temp.outputStream().use { source.copyTo(it) }
                    }
                    temp
                }
            }
            working = null
            result
                .onSuccess { staged = it }
                .onFailure { problem = it.message; }
        }
    }

    LaunchedEffect(Unit) {
        VaultSession.expectingExternalActivity = true
        pick.launch(arrayOf("*/*"))
    }

    fun open() {
        val file = staged ?: return
        val words = Wordlist.of(context).parse(typed)
        if (words == null || words.size < 6) {
            problem = "That is not six words from the list."
            return
        }
        working = "Opening"
        problem = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val reader = BundleReader.open(file, words)
                    reader.extractInto(vault)
                }
            }
            working = null
            file.delete()
            staged = null
            outcome
                .onSuccess {
                    imported = it
                    VaultSession.noteContentsChanged()
                }
                .onFailure {
                    problem = when (it) {
                        is BundleReader.Companion.WrongPhrase -> "That code does not open this file."
                        else -> it.message ?: "That file could not be opened."
                    }
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open a shared file") },
                navigationIcon = {
                    IconButton(
                        onClick = { staged?.delete(); onDone() },
                        enabled = working == null,
                    ) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        ) {
            when {
                working != null -> Centred {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(working!!, style = MaterialTheme.typography.bodyMedium)
                }

                imported != null -> Centred {
                    Text("Added $imported items", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onDone) { Text("Done") }
                }

                staged != null -> {
                    Text("Enter the six words", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Spelling is corrected, order is not.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it; problem = null },
                        label = { Text("Code") },
                        isError = problem != null,
                        supportingText = problem?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { open() }),
                        trailingIcon = {
                            IconButton(onClick = { scanning = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_scan),
                                    contentDescription = "Scan the code",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { open() },
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open") }
                }

                else -> Centred {
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                    }
                    Button(onClick = {
                        VaultSession.expectingExternalActivity = true
                        pick.launch(arrayOf("*/*"))
                    }) { Text("Choose a file") }
                }
            }
        }
    }

    if (scanning) {
        Dialog(
            onDismissRequest = { scanning = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            QrScanner(
                lifecycleOwner = activity,
                onCode = { text ->
                    scanning = false
                    typed = text
                    problem = null
                    open()
                },
                onCancel = { scanning = false },
            )
        }
    }
}