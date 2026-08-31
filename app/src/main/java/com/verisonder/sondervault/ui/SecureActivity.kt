package com.verisonder.sondervault.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity

/**
 * Every activity in the app extends this.
 *
 * FLAG_SECURE is set before anything is drawn, and it is set here rather than per screen
 * because a screen that forgets it is a screen that leaks, and the only reliable way to
 * not forget is to have no opportunity to.
 *
 * What it stops: screenshots, screen recording, casting, and the thumbnail Android puts
 * in the recents list.
 *
 * What it does not stop: a second phone pointed at the screen, and a rooted device. The
 * README says so, and any copy shown to the user should say so too rather than implying
 * the screen cannot be captured at all.
 *
 * FragmentActivity rather than ComponentActivity because BiometricPrompt requires one.
 */
abstract class SecureActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
