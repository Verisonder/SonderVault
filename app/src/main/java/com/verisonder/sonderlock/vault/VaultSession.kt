package com.verisonder.sonderlock.vault

import androidx.compose.runtime.mutableStateOf

/**
 * The one place an open vault lives.
 *
 * Holding a vault open means the master key is in memory, so this is closed the moment
 * the app leaves the screen rather than on a timer. A vault that stays unlocked in the
 * background is a vault that is open while the phone is in someone else's hand.
 *
 * Backed by Compose state so the screens follow it without anyone having to remember to
 * navigate.
 */
object VaultSession {

    private val state = mutableStateOf<VaultStore.Opened?>(null)

    val opened: VaultStore.Opened? get() = state.value
    val isOpen: Boolean get() = state.value != null
    val isDecoy: Boolean get() = state.value?.isDecoy == true

    /**
     * Set while an activity the app itself started is in front — the photo picker, the
     * system share sheet. Without it, locking on background would fire the instant the
     * picker opened and the vault would be shut by the time a photo came back.
     */
    var expectingExternalActivity: Boolean = false

    /**
     * Bumped whenever the vault's contents change. Screens read it so a grid refreshes
     * after an import without the import having to know which screens exist.
     */
    private val changes = mutableStateOf(0)
    val contentsChanged: Int get() = changes.value

    fun noteContentsChanged() {
        changes.value += 1
    }

    fun open(opened: VaultStore.Opened) {
        state.value?.vault?.close()
        state.value = opened
    }

    fun lock() {
        state.value?.vault?.close()
        state.value = null
    }

    fun lockUnlessLeavingBriefly() {
        if (expectingExternalActivity) {
            expectingExternalActivity = false
            return
        }
        lock()
    }
}
