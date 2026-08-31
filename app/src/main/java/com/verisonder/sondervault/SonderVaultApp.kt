package com.verisonder.sondervault

import android.app.Application

class SonderVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
