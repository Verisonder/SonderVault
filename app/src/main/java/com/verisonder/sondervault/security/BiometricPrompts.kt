package com.verisonder.sondervault.security

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.vault.Vault
import java.io.File
import java.util.concurrent.Executor

/**
 * Thin wrapper over BiometricPrompt.
 *
 * The prompt text says "SonderVault" and nothing about what is behind it. A system dialog
 * reading "unlock your hidden photos" would appear over whatever app is in front, which
 * is the one place the app has no control over who is looking.
 */
object BiometricPrompts {

    private class Callbacks(
        val onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        val onFailure: (String?) -> Unit,
    ) : BiometricPrompt.AuthenticationCallback() {

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
            onSuccess(result)

        override fun onAuthenticationError(code: Int, message: CharSequence) {
            // A cancel is a choice, not a failure, and should say nothing.
            val silent = code == BiometricPrompt.ERROR_USER_CANCELED ||
                code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                code == BiometricPrompt.ERROR_CANCELED
            onFailure(if (silent) null else message.toString())
        }
    }

    private fun executor(activity: FragmentActivity): Executor =
        androidx.core.content.ContextCompat.getMainExecutor(activity)

    private fun info(subtitle: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle("SonderVault")
        .setSubtitle(subtitle)
        .setNegativeButtonText("Use password")
        .setConfirmationRequired(false)
        .build()

    /**
     * @param vault must be the real one. A fingerprint that opens the decoy is a
     *   fingerprint that can be pressed against the phone by someone holding your hand,
     *   and it would also make the next unlock land in the decoy without saying so. This
     *   refuses rather than trusting the screen to have checked.
     */
    fun enable(
        activity: FragmentActivity,
        baseDir: File,
        vault: Vault,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (vault.isDecoy) {
            onResult(false, "Fingerprint unlock is not available here.")
            return
        }
        val masterKey = vault.masterKeyCopy()
        val cipher = runCatching { BiometricKey.cipherForEnabling() }.getOrNull()
        if (cipher == null) {
            Crypto.wipe(masterKey)
            onResult(false, "This device would not create the key")
            return
        }
        BiometricPrompt(
            activity,
            executor(activity),
            Callbacks(
                onSuccess = { result ->
                    val ready = result.cryptoObject?.cipher
                    if (ready == null) {
                        onResult(false, "No cipher came back from the prompt")
                    } else {
                        runCatching { BiometricKey.store(baseDir, ready, masterKey) }
                            .onSuccess { Crypto.wipe(masterKey); onResult(true, null) }
                            .onFailure { Crypto.wipe(masterKey); onResult(false, it.message) }
                    }
                },
                onFailure = { message -> onResult(false, message) },
            ),
        ).authenticate(info("Confirm to turn on fingerprint unlock"), BiometricPrompt.CryptoObject(cipher))
    }

    fun unlock(
        activity: FragmentActivity,
        baseDir: File,
        onResult: (ByteArray?, String?) -> Unit,
    ) {
        val cipher = BiometricKey.cipherForUnlocking(baseDir)
        if (cipher == null) {
            // Most often this is the key being gone because a fingerprint was enrolled
            // since. Say what to do rather than what happened.
            onResult(null, "Fingerprint unlock is unavailable. Use your password.")
            return
        }
        BiometricPrompt(
            activity,
            executor(activity),
            Callbacks(
                onSuccess = { result ->
                    val ready = result.cryptoObject?.cipher
                    val key = ready?.let { BiometricKey.unwrap(baseDir, it) }
                    onResult(key, if (key == null) "Could not unwrap the key" else null)
                },
                onFailure = { message -> onResult(null, message) },
            ),
        ).authenticate(info("Unlock"), BiometricPrompt.CryptoObject(cipher))
    }
}
