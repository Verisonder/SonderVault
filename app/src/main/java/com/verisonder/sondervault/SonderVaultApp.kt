package com.verisonder.sondervault

import android.app.Application

class SonderVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // A bundle copied in for restoring, left behind if the app was closed halfway.
        // It is encrypted, but it has no reason to stay.
        java.io.File(cacheDir, "incoming.sondervault").delete()
    }
}
