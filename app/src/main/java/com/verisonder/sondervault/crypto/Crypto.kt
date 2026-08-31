package com.verisonder.sondervault.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives shared by every format in the app. Nothing here knows what a vault is.
 *
 * Argon2id comes from Bouncy Castle's lightweight API, called directly rather than
 * registered as a JCE provider: Android ships its own Bouncy Castle repackaged under
 * com.android.org.bouncycastle, and registering a second one is how provider conflicts
 * start. The lightweight classes have no such problem.
 */
object Crypto {

    const val KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val MAC_BYTES = 32

    const val ARGON_MEM_KIB = 65536
    const val ARGON_ITERS = 3
    const val ARGON_PAR = 2

    private val rng = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun randomKey(): ByteArray = random(KEY_BYTES)

    // ------------------------------------------------------------------ Argon2id

    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memKiB: Int = ARGON_MEM_KIB,
        iterations: Int = ARGON_ITERS,
        parallelism: Int = ARGON_PAR,
        outputBytes: Int = KEY_BYTES,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(outputBytes)
        generator.generateBytes(password, out)
        return out
    }

    /** UTF-8 bytes of a password, so callers can wipe the array afterwards. */
    fun utf8(text: CharArray): ByteArray {
        val buffer = java.nio.CharBuffer.wrap(text)
        val encoded = Charsets.UTF_8.encode(buffer)
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        return out
    }

    // --------------------------------------------------------------------- HKDF

    /**
     * HKDF-SHA256, expand only. Extract is skipped deliberately: every input here is
     * already a uniform 256-bit key, and running extract over one buys nothing.
     */
    fun hkdf(key: ByteArray, info: ByteArray, length: Int = KEY_BYTES): ByteArray {
        require(length <= 255 * MAC_BYTES) { "HKDF output too long" }
        val mac = Mac.getInstance("HmacSHA256")
        val out = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - written)
            System.arraycopy(block, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    fun hkdf(key: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray =
        hkdf(key, info.toByteArray(Charsets.US_ASCII), length)

    // ---------------------------------------------------------------------- GCM

    fun gcmSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BYTES * 8, nonce),
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    /** Null on any authentication failure. A wrong password is not an exceptional event. */
    fun gcmOpen(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BYTES * 8, nonce),
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        cipher.doFinal(sealed)
    } catch (e: AEADBadTagException) {
        null
    } catch (e: javax.crypto.BadPaddingException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------------- CTR

    /**
     * AES-256-CTR positioned at an absolute counter value.
     *
     * The counter block is nonce(8) || uint64be(counter), and the counter is global
     * across a file rather than restarting per block. That is what makes a block
     * boundary a counter boundary, and therefore what makes seeking free.
     */
    fun ctrCipher(key: ByteArray, nonce8: ByteArray, counter: Long): Cipher {
        require(nonce8.size == 8) { "CTR nonce must be 8 bytes" }
        val iv = ByteArray(16)
        System.arraycopy(nonce8, 0, iv, 0, 8)
        putLongBE(iv, 8, counter)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    fun ctr(key: ByteArray, nonce8: ByteArray, counter: Long, data: ByteArray): ByteArray =
        ctrCipher(key, nonce8, counter).doFinal(data)

    // --------------------------------------------------------------------- HMAC

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ------------------------------------------------------------------- helpers

    fun putLongBE(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }

    fun getLongBE(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        return value
    }

    fun putIntBE(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }

    fun getIntBE(source: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (source[offset + i].toInt() and 0xFF)
        return value
    }

    fun longBE(value: Long): ByteArray = ByteArray(8).also { putLongBE(it, 0, value) }

    /**
     * Zero a key that is finished with. Not a guarantee — the JVM may have copied it
     * during a GC move — but it shortens the window and costs nothing.
     */
    fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) if (array != null) Arrays.fill(array, 0)
    }
}
