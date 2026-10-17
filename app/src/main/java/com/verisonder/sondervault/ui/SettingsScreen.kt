package com.verisonder.sondervault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.Preferences
import com.verisonder.sondervault.security.BiometricKey
import com.verisonder.sondervault.security.BiometricPrompts
import com.verisonder.sondervault.ui.theme.AccentChoices
import com.verisonder.sondervault.ui.theme.Look
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.ThemeMode
import com.verisonder.sondervault.ui.theme.display
import com.verisonder.sondervault.ui.theme.onColor
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultSession
import com.verisonder.sondervault.vault.VaultStore

/**
 * Everything that is not looking at photos.
 *
 * The duress row is worded so that it reads as a feature rather than as a confession.
 * Nothing on this screen states whether a duress password is currently set, because
 * someone made to open the vault could be made to open settings too.
 */
@Composable
fun SettingsScreen(
    store: VaultStore,
    vault: Vault,
    activity: FragmentActivity,
    onDuress: () -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val items = remember(vault, VaultSession.contentsChanged) { vault.items() }
    val used = remember(VaultSession.contentsChanged) { store.totalSizeOnDisk() }
    // Absent inside the decoy for the same reason as the duress row: turning it on there
    // would wrap the decoy's key, and every later unlock would land in the decoy while
    // claiming to be the real vault. That is how a vault got lost.
    val fingerprintPossible = remember { !vault.isDecoy && BiometricKey.isAvailable(activity) }
    var fingerprintOn by remember(VaultSession.foregroundCount) {
        mutableStateOf(BiometricKey.isEnabled(activity.filesDir))
    }
    var note by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        TopBar(onBack = onClose)
        ScreenHeader("Settings.")

        Section(1, "Vault", first = true) {
            // Absent inside the decoy, and not merely disabled. Someone who forced this
            // vault open can scroll settings too, and a greyed-out row named "Duress
            // password" tells them exactly what they are looking at and that there is
            // more to find.
            if (!vault.isDecoy) {
                LinkCard("Duress password", "Opens a different set of photos.", onDuress)
            }
            if (fingerprintPossible || BiometricKey.isEnabled(activity.filesDir)) ToggleCard(
                title = "Fingerprint unlock",
                sub = when {
                    !fingerprintPossible -> "No fingerprint is set up on this phone."
                    note != null -> note!!
                    fingerprintOn -> "Also confirms when files leave the vault."
                    else -> "Your password always works either way."
                },
                checked = fingerprintOn,
                enabled = fingerprintPossible,
                onChange = { wanted ->
                    note = null
                    if (!wanted) {
                        BiometricKey.disable(activity.filesDir)
                        fingerprintOn = false
                    } else {
                        BiometricPrompts.enable(activity, activity.filesDir, vault) { ok, message ->
                            fingerprintOn = ok
                            if (!ok) note = message
                        }
                    }
                },
            )
        }

        Section(2, "Files") {
            LinkCard("Back up everything", "One encrypted file with its own code.", onBackUp)
            LinkCard("Open a shared file", "Add items from a file someone sent you, or from a backup.", onRestore)
        }

        Section(3, "Look") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.filter { it != ThemeMode.You || ThemeMode.youAvailable }.forEach { mode ->
                    Chip(mode.label, Look.mode == mode) {
                        Look.mode = mode
                        Preferences.setTheme(context, mode.id)
                    }
                }
            }
            if (Look.mode != ThemeMode.You) {
                Text(
                    "Accent",
                    color = Palette.dim, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 10.dp),
                )
                AccentChoices.chunked(6).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEach { c ->
                            val picked = c.toArgb() == Look.accent.toArgb()
                            Box(
                                Modifier.size(42.dp).pressScale().clip(CircleShape).background(c)
                                    .border(3.dp, if (picked) Palette.text else Color.Transparent, CircleShape)
                                    .clickable {
                                        Look.accent = c
                                        Preferences.setAccent(context, c.toArgb())
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (picked) Icon(LineIcons.check, null, tint = onColor(c), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        Section(4, "This vault") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${items.size}", if (items.size == 1) "item" else "items", Modifier.weight(1f))
                StatTile("${used / 1024 / 1024} MB", "on this phone", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Uninstalling SonderVault deletes everything in it. A backup is the only " +
                    "way to get it back.",
                color = Palette.dim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(blockShape).background(Palette.panel).padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(value, style = display(24), color = Palette.accentText, maxLines = 1)
        Text(label, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
