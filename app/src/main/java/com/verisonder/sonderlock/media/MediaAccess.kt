package com.verisonder.sonderlock.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.ActivityCompat
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

    /**
     * Android 14 offers "Select photos" beside "Allow all", which grants access to a
     * handful of chosen items instead of the library.
     *
     * That is not enough here and cannot be made to be. The app has to show the whole
     * library to pick from, and it has to delete the originals afterwards — neither works
     * on a hand-picked subset. Worth detecting rather than treating as a plain refusal,
     * because the user did say yes and telling them they said no is infuriating.
     */
    fun hasPartialAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (hasReadAccess(context)) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether asking again would show anything.
     *
     * Android stops showing the dialog after two refusals, so the request silently does
     * nothing and the button looks broken. Past that point the only route is the app's
     * own settings page.
     */
    fun canAskAgain(activity: Activity): Boolean =
        readPermissions().any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
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
