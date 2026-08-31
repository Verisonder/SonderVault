package com.verisonder.sonderlock.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import com.verisonder.sonderlock.crypto.Crypto
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * A second wrapping of the same master key, held by the Android Keystore and released
 * only by a fingerprint.
 *
 * The key never leaves the secure hardware and this app never sees it — the Cipher comes
 * back already unlocked, and all we do is push bytes through it. `bio.bin` holds the
 * wrapped master key and nothing else.
 *
 * `setInvalidatedByBiometricEnrollment` means adding a new fingerprint destroys this key.
 * That is the correct behaviour and not an inconvenience: without it, anyone who could
 * enrol their own finger on an unlocked phone would have added themselves to the vault.
 * The password still opens it, so nothing is lost.
 */
object BiometricKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "sonderlock.biometric.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FILE_NAME = "bio.bin"

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(baseDir: File): Boolean = wrappedFile(baseDir).exists() && hasKey()

    /**
     * A Cipher ready to wrap the master key. Hand it to BiometricPrompt; it comes back
     * usable only after the fingerprint is accepted.
     */
    fun cipherForEnabling(): Cipher {
        deleteKey()
        generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
    }

    fun cipherForUnlocking(baseDir: File): Cipher? {
        val stored = readWrapped(baseDir) ?: return null
        val key = runCatching { secretKey() }.getOrNull() ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored.iv))
            }
        }.getOrNull()
    }

    /** Called after the prompt succeeds, with the Cipher it handed back. */
    fun store(baseDir: File, cipher: Cipher, masterKey: ByteArray) {
        val sealed = cipher.doFinal(masterKey)
        val iv = cipher.iv
        val out = ByteArray(1 + iv.size + sealed.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(sealed, 0, out, 1 + iv.size, sealed.size)
        wrappedFile(baseDir).writeBytes(out)
    }

    fun unwrap(baseDir: File, cipher: Cipher): ByteArray? {
        val stored = readWrapped(baseDir) ?: return null
        return runCatching { cipher.doFinal(stored.sealed) }.getOrNull()
    }

    fun disable(baseDir: File) {
        wrappedFile(baseDir).delete()
        deleteKey()
    }

    // ------------------------------------------------------------------- internals

    private class Wrapped(val iv: ByteArray, val sealed: ByteArray)

    private fun wrappedFile(baseDir: File) = File(baseDir, FILE_NAME)

    private fun readWrapped(baseDir: File): Wrapped? {
        val file = wrappedFile(baseDir)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 2) return null
        val ivLength = bytes[0].toInt() and 0xFF
        if (bytes.size < 1 + ivLength + Crypto.GCM_TAG_BYTES) return null
        return Wrapped(
            iv = bytes.copyOfRange(1, 1 + ivLength),
            sealed = bytes.copyOfRange(1 + ivLength, bytes.size),
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    private fun secretKey() = keyStore().getKey(ALIAS, null) as javax.crypto.SecretKey

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun generateKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        // StrongBox is a separate security chip and not every phone has one. Asking for
        // it throws rather than degrading, so the fallback is explicit.
        try {
            generator.init(specBuilder().setIsStrongBoxBacked(true).build())
            generator.generateKey()
            return
        } catch (e: StrongBoxUnavailableException) {
            // fall through
        } catch (e: java.security.ProviderException) {
            // some devices report StrongBox and then fail generating into it
        }
        generator.init(specBuilder().build())
        generator.generateKey()
    }

    private fun specBuilder() = KeyGenParameterSpec.Builder(
        ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(true)
        .setInvalidatedByBiometricEnrollment(true)
}
