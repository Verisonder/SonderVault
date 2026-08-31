package com.verisonder.sonderlock

import android.app.Application

class SonderLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
