package com.verisonder.sondervault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.display
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.bundle.BundleReader
import com.verisonder.sondervault.vault.Vault
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

    Column(Modifier.fillMaxSize().background(Palette.bg).navigationBarsPadding().imePadding()) {
        TopBar(onBack = { staged?.delete(); onDone() }, close = true, enabled = working == null)
        ScreenHeader("Open a shared file.", kicker = "Restore")
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = Gutter, vertical = 16.dp)) {
            when {
                working != null -> Centred {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(16.dp))
                    Text(working!!, color = Palette.dim, fontSize = 14.sp)
                }

                imported != null -> Centred {
                    Text("$imported", style = display(48), color = Palette.accentText)
                    Text("items added", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    PillButton("Done", onClick = onDone)
                }

                staged != null -> {
                    Kicker("Enter the six words", Modifier.padding(bottom = 8.dp))
                    Text(
                        "Spelling is corrected, order is not.",
                        color = Palette.dim,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    InputField(
                        value = typed,
                        onChange = { typed = it; problem = null },
                        placeholder = "Code",
                        problem = problem,
                        imeAction = ImeAction.Go,
                        onAction = { open() },
                        trailing = {
                            IconPill(LineIcons.scan, "Scan the code") { scanning = true }
                        },
                        modifier = Modifier.enter(0),
                    )
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        "Open",
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().enter(1),
                    ) { open() }
                }

                else -> Centred {
                    problem?.let {
                        Text(it, color = Palette.danger, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                    }
                    PillButton("Choose a file") {
                        VaultSession.expectingExternalActivity = true
                        pick.launch(arrayOf("*/*"))
                    }
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