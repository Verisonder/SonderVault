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

    private fun of(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the fingerprint suggestion has been turned down for good. */
    fun fingerprintOfferDeclined(context: Context): Boolean =
        of(context).getBoolean(NO_FINGERPRINT_OFFER, false)

    fun declineFingerprintOffer(context: Context) {
        of(context).edit().putBoolean(NO_FINGERPRINT_OFFER, true).apply()
    }
}
