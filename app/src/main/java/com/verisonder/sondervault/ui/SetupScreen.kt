package com.verisonder.sondervault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore
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

    fun create() {
        if (!ready) return
        working = true
        problem = null
        scope.launch {
            // Argon2id at 64 MiB takes a moment on a phone and must not run on the
            // thread drawing the screen.
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val configured = withPasswordBytes(password) { store.configure(it, null, false) }
                    VaultStore.Opened(configured.real, isDecoy = false, wiped = false)
                }
            }
            working = false
            result
                .onSuccess { password = ""; confirm = ""; VaultSession.open(it); onDone() }
                .onFailure { problem = it.message ?: "Could not create the vault" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.padding(horizontal = Gutter).enter(0)) { Wordmark(22) }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.enter(1)) {
            ScreenHeader(
                title = "Choose a password.",
                kicker = "New vault",
                sub = "It is the only way in. It cannot be reset or recovered.",
            )
        }

        Column(Modifier.padding(horizontal = Gutter).padding(top = 24.dp)) {
            InputField(
                value = password,
                onChange = { password = it; problem = null },
                placeholder = "Password",
                password = true,
                enabled = !working,
                problem = if (tooShort) "At least $MINIMUM_LENGTH characters" else null,
                imeAction = ImeAction.Next,
                modifier = Modifier.enter(2),
            )

            Spacer(Modifier.height(12.dp))

            InputField(
                value = confirm,
                onChange = { confirm = it; problem = null },
                placeholder = "Password again",
                password = true,
                enabled = !working,
                problem = if (mismatch) "These do not match" else problem,
                imeAction = ImeAction.Done,
                onAction = { create() },
                modifier = Modifier.enter(3),
            )

            Spacer(Modifier.height(24.dp))

            PillButton(
                "Create vault",
                enabled = ready || working,
                working = working,
                onClick = { create() },
                modifier = Modifier.fillMaxWidth().enter(4),
            )

            Spacer(Modifier.height(20.dp))
            Block(Modifier.enter(5)) {
                Text(
                    "Uninstalling the app deletes everything in it. You can export a backup later.",
                    color = Palette.dim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
