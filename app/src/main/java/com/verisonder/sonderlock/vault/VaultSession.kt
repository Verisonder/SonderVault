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

    private const val EXTERNAL_WINDOW_MS = 2 * 60 * 1000L

    private val state = mutableStateOf<VaultStore.Opened?>(null)

    val opened: VaultStore.Opened? get() = state.value
    val isOpen: Boolean get() = state.value != null
    // Read off the vault, not off how it happened to be opened. A biometric unlock
    // used to report the real vault whatever it had actually opened.
    val isDecoy: Boolean get() = state.value?.vault?.isDecoy == true

    /**
     * Set while an activity the app itself started is in front — the document picker, the
     * delete confirmation, a settings page. Without it, locking on background fires the
     * instant that activity opens, and the screen waiting for the result is gone by the
     * time it arrives. That is what made sharing write the file and then never show the
     * code.
     *
     * Cleared as soon as the result comes back, and expired after a couple of minutes.
     * A flag left standing would mean the next real trip to the background did not lock,
     * which is a worse failure than the one it exists to prevent.
     */
    private var externalUntil: Long = 0

    var expectingExternalActivity: Boolean
        get() = System.currentTimeMillis() < externalUntil
        set(value) {
            externalUntil = if (value) System.currentTimeMillis() + EXTERNAL_WINDOW_MS else 0
        }

    /** Called when a launcher result arrives, whatever the result was. */
    fun externalActivityFinished() {
        externalUntil = 0
    }

    /**
     * Bumped whenever the vault's contents change. Screens read it so a grid refreshes
     * after an import without the import having to know which screens exist.
     */
    private val changes = mutableStateOf(0)
    val contentsChanged: Int get() = changes.value

    fun noteContentsChanged() {
        changes.value += 1
    }

    /**
     * Bumped every time the app comes back to the foreground. Screens key on it to
     * re-read things that can only be changed outside the app — a permission toggled in
     * Settings is invisible otherwise, and the screen would still be asking for something
     * the user had already granted.
     */
    private val foregrounds = mutableStateOf(0)
    val foregroundCount: Int get() = foregrounds.value

    fun noteForeground() {
        foregrounds.value += 1
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
        if (expectingExternalActivity) return
        lock()
    }
}
