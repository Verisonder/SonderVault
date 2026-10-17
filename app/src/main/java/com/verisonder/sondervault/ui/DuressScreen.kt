package com.verisonder.sondervault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.ui.theme.Palette
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

    fun submit() {
        if (!ready) return
        working = true
        problem = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    if (alreadySet) {
                        val ok = withPasswordBytes(current) { c ->
                            withPasswordBytes(duress) { d -> store.changeDuress(c, d, wipes) }
                        }
                        if (!ok) error("wrong") else null
                    } else {
                        withPasswordBytes(current) { c ->
                            withPasswordBytes(duress) { d -> store.setDuress(c, vault, d, wipes) }
                        } ?: error("failed")
                    }
                }
            }
            working = false
            result
                .onSuccess { decoy ->
                    // A vault that already has its photos does not need to be filled again.
                    if (decoy != null) onChooseDecoyPhotos(decoy) else onClose()
                }
                .onFailure {
                    val wait = store.remainingLockoutMs()
                    problem = if (wait > 0L) {
                        "Too many attempts. Try again in ${countdown(wait)}."
                    } else if (alreadySet) {
                        "That is not your current duress password."
                    } else {
                        "That is not your password."
                    }
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        TopBar(onBack = onClose, close = true, enabled = !working)
        ScreenHeader(
            "Duress password.",
            sub = if (alreadySet) {
                "Change the password, or what happens when it is used."
            } else {
                "A duress password that opens a different set of photos. Anyone made " +
                    "to hand over a password can hand over this one."
            },
        )

        Section(1, "Passwords", first = true) {
            InputField(
                value = current,
                onChange = { current = it; problem = null },
                placeholder = if (alreadySet) "Current duress password" else "Your password",
                password = true,
                enabled = !working,
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(12.dp))
            InputField(
                value = duress,
                onChange = { duress = it; problem = null },
                placeholder = if (alreadySet) "New duress password" else "Duress password",
                password = true,
                enabled = !working,
                problem = when {
                    sameAsMain -> "It has to be different from the one above."
                    tooShort -> "At least $MINIMUM_LENGTH characters"
                    else -> null
                },
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(12.dp))
            InputField(
                value = confirm,
                onChange = { confirm = it; problem = null },
                placeholder = if (alreadySet) "New duress password again" else "Duress password again",
                password = true,
                enabled = !working,
                problem = if (mismatch) "These do not match" else null,
                imeAction = ImeAction.Done,
                onAction = { submit() },
            )
        }

        Section(2, "When it is used") {
            OptionCard(
                "Open the other photos",
                "Nothing is destroyed. Works every time it is used.",
                selected = !wipes,
                onClick = { wipes = false },
            )
            OptionCard(
                "Open them and erase the real vault",
                "Everything you were hiding is gone, permanently and immediately.",
                selected = wipes,
                onClick = { wipes = true },
            )

            if (wipes) {
                Block(Modifier.padding(top = 4.dp).enter(0), color = Palette.danger.copy(alpha = 0.16f)) {
                    Text(
                        "Typing this password by mistake destroys everything. Make it " +
                            "nothing like your own.",
                        color = Palette.text,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = Gutter).padding(top = 24.dp)) {
            problem?.let {
                Text(it, color = Palette.danger, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
            }

            PillButton(
                "Continue",
                enabled = ready || working,
                working = working,
                danger = wipes,
                modifier = Modifier.fillMaxWidth(),
            ) { submit() }

            if (!alreadySet) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Next you choose which photos the duress password shows. Pick " +
                        "ordinary ones — an empty vault gives the game away.",
                    color = Palette.dim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            if (alreadySet) {
                Spacer(Modifier.height(20.dp))
                PillButton(
                    "Remove the duress password",
                    filled = false,
                    enabled = current.isNotEmpty() && !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    working = true
                    problem = null
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            withPasswordBytes(current) { store.removeDuress(it) }
                        }
                        working = false
                        val wait = store.remainingLockoutMs()
                        if (ok) onClose()
                        else if (wait > 0L) problem = "Too many attempts. Try again in ${countdown(wait)}."
                        else problem = "That is not your current duress password."
                    }
                }
                Text(
                    "The photos it shows are deleted with it.",
                    color = Palette.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 6.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            TextAction("Cancel", enabled = !working, color = Palette.dim, onClick = onClose)
            Spacer(Modifier.height(24.dp))
        }
    }
}
