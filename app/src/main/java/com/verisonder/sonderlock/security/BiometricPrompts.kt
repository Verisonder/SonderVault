package com.verisonder.sonderlock.security

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.concurrent.Executor

/**
 * Thin wrapper over BiometricPrompt.
 *
 * The prompt text says "SonderLock" and nothing about what is behind it. A system dialog
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
        .setTitle("SonderLock")
        .setSubtitle(subtitle)
        .setNegativeButtonText("Use password")
        .setConfirmationRequired(false)
        .build()

    fun enable(
        activity: FragmentActivity,
        baseDir: File,
        masterKey: ByteArray,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val cipher = runCatching { BiometricKey.cipherForEnabling() }.getOrNull()
        if (cipher == null) {
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
                            .onSuccess { onResult(true, null) }
                            .onFailure { onResult(false, it.message) }
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
