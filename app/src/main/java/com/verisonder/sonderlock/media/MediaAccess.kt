package com.verisonder.sonderlock.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * What the app is allowed to do with the phone's own photo library.
 *
 * Two separate things, and they fail differently:
 *
 * - **Read access** is an ordinary runtime permission. Without it there is no grid to
 *   pick from and importing cannot start at all.
 * - **Media management** is a special permission granted by a toggle in Settings, not by
 *   a popup. With it, originals disappear on import. Without it, every import ends in one
 *   system confirmation dialog. The app works either way and never insists.
 *
 * Media management is Android 12 and above. Below that the dialog is the only route and
 * there is nothing to ask for.
 */
object MediaAccess {

    fun readPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasReadAccess(context: Context): Boolean = readPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun mediaManagementPossible(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /** Opens the Settings page holding the toggle. There is no in-app way to grant it. */
    fun mediaManagementSettings(context: Context): Intent? {
        if (!mediaManagementPossible()) return null
        return Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
    }
}
