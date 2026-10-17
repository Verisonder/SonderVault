package com.verisonder.sondervault

import android.content.Context

/**
 * The handful of choices that have to outlive a session.
 *
 * Deliberately tiny and deliberately not encrypted: nothing here says anything about what
 * is in the vault, only what the person has already told the app to stop asking. Storing
 * that alongside the encrypted content would be the wrong shape — it needs reading before
 * anything is unlocked.
 */
object Preferences {

    private const val FILE = "preferences"
    private const val NO_FINGERPRINT_OFFER = "no_fingerprint_offer"
    private const val THEME = "theme"
    private const val ACCENT = "accent"

    private fun of(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the fingerprint suggestion has been turned down for good. */
    fun fingerprintOfferDeclined(context: Context): Boolean =
        of(context).getBoolean(NO_FINGERPRINT_OFFER, false)

    fun declineFingerprintOffer(context: Context) {
        of(context).edit().putBoolean(NO_FINGERPRINT_OFFER, true).apply()
    }

    /** The look picked in Settings. Says nothing about the contents. */
    fun theme(context: Context): String? = of(context).getString(THEME, null)

    fun setTheme(context: Context, id: String) {
        of(context).edit().putString(THEME, id).apply()
    }

    /** Accent colour as ARGB, or null for the default. */
    fun accent(context: Context): Int? =
        of(context).let { if (it.contains(ACCENT)) it.getInt(ACCENT, 0) else null }

    fun setAccent(context: Context, argb: Int) {
        of(context).edit().putInt(ACCENT, argb).apply()
    }
}
