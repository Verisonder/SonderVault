package com.verisonder.sonderlock.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import com.verisonder.sonderlock.ui.theme.SonderLockTheme
import com.verisonder.sonderlock.vault.VaultSession
import com.verisonder.sonderlock.vault.VaultStore

class MainActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = VaultStore(filesDir)
        setContent {
            SonderLockTheme {
                AppRoot(store, this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        VaultSession.noteForeground()
    }

    /**
     * Locking here rather than on a timer. The vault is open exactly as long as it is on
     * screen, so a phone taken out of someone's hand is a phone with a locked vault.
     *
     * onStop also fires when the app starts something itself — the photo picker, a share
     * sheet — which is why the session can be told to expect that and skip one lock.
     */
    override fun onStop() {
        super.onStop()
        VaultSession.lockUnlessLeavingBriefly()
    }
}
