package com.verisonder.sondervault.crypto

import java.security.SecureRandom

/**
 * slots.bin — the wrapped master keys. See docs/FORMAT.md section 2.
 *
 * The file is 302 bytes whatever it contains. A vault with no duress password is
 * byte-for-byte the same shape as one with, unused slots hold random bytes, and slot
 * order is shuffled at write time. Nothing outside a sealed payload says what a slot
 * opens or whether it opens anything.
 *
 * The biometric wrapping is not here: it lives in the Android Keystore, where its
 * existence is already visible and hiding it would be pretence.
 */
object KeySlots {

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    const val VERSION = 1

    const val SLOT_COUNT = 4
    const val SLOT_SIZE = 68            // 12 nonce + 56 sealed
    const val HEADER_SIZE = 30          // magic 4, version 1, argon 9, salt 16
    const val PAYLOAD_SIZE = 40         // vaultId 1, flags 1, padding 6, key 32
    const val FILE_SIZE = HEADER_SIZE + SLOT_COUNT * SLOT_SIZE  // 302
    const val SALT_BYTES = 16

    const val VAULT_REAL = 0
    const val VAULT_DECOY = 1

    private const val FLAG_WIPE = 0x01

    private val rng = SecureRandom()

    /**
     * @param wipe destroy the real vault when this password is used. Only meaningful on
     *   a duress entry, and independent of it: a duress password can open the decoy
     *   without destroying anything, which is what makes it usable more than once.
     */
    class Entry(
        val password: ByteArray,
        val vaultId: Int,
        val wipe: Boolean,
        val masterKey: ByteArray,
    )

    class Unlocked(
        val slotIndex: Int,
        val vaultId: Int,
        val wipe: Boolean,
        val masterKey: ByteArray,
    ) {
        val isDecoy: Boolean get() = vaultId == VAULT_DECOY
    }

    fun build(
        entries: List<Entry>,
        memKiB: Int = Crypto.ARGON_MEM_KIB,
        iterations: Int = Crypto.ARGON_ITERS,
        parallelism: Int = Crypto.ARGON_PAR,
    ): ByteArray {
        require(entries.isNotEmpty()) { "at least one slot is needed" }
        require(entries.size <= SLOT_COUNT) { "at most $SLOT_COUNT slots" }

        val salt = Crypto.random(SALT_BYTES)
        val header = header(salt, memKiB, iterations, parallelism)

        val slots = ArrayList<ByteArray>(SLOT_COUNT)
        for (entry in entries) {
            require(entry.masterKey.size == Crypto.KEY_BYTES) { "master key must be 32 bytes" }
            val kek = Crypto.argon2id(entry.password, salt, memKiB, iterations, parallelism)
            val payload = ByteArray(PAYLOAD_SIZE)
            payload[0] = entry.vaultId.toByte()
            payload[1] = if (entry.wipe) FLAG_WIPE.toByte() else 0
            System.arraycopy(Crypto.random(6), 0, payload, 2, 6)
            System.arraycopy(entry.masterKey, 0, payload, 8, Crypto.KEY_BYTES)

            val nonce = Crypto.random(Crypto.GCM_NONCE_BYTES)
            val sealed = Crypto.gcmSeal(kek, nonce, payload, header)
            slots.add(nonce + sealed)

            Crypto.wipe(kek, payload)
        }
        // filler is indistinguishable from a real slot: random bytes are exactly what a
        // correctly sealed slot looks like to anyone without the key
        while (slots.size < SLOT_COUNT) slots.add(Crypto.random(SLOT_SIZE))

        shuffle(slots)

        val out = ByteArray(FILE_SIZE)
        System.arraycopy(header, 0, out, 0, HEADER_SIZE)
        for (i in 0 until SLOT_COUNT) {
            System.arraycopy(slots[i], 0, out, HEADER_SIZE + i * SLOT_SIZE, SLOT_SIZE)
        }
        return out
    }

    /**
     * One Argon2 run, then four cheap GCM attempts. Null if the password opens nothing.
     *
     * The caller must not branch differently on which slot index answered: the index is
     * returned for re-wrapping, not for deciding anything the user can observe.
     */
    fun unlock(blob: ByteArray, password: ByteArray): Unlocked? {
        if (blob.size != FILE_SIZE) return null
        if (!Crypto.constantTimeEquals(blob.copyOfRange(0, 4), MAGIC)) return null
        if (blob[4].toInt() != VERSION) return null

        val memKiB = Crypto.getIntBE(blob, 5)
        val iterations = Crypto.getIntBE(blob, 9)
        val parallelism = blob[13].toInt() and 0xFF
        if (memKiB !in 8..1_048_576 || iterations !in 1..64 || parallelism !in 1..16) return null

        val salt = blob.copyOfRange(14, 14 + SALT_BYTES)
        val header = blob.copyOfRange(0, HEADER_SIZE)

        val kek = Crypto.argon2id(password, salt, memKiB, iterations, parallelism)
        try {
            for (i in 0 until SLOT_COUNT) {
                val offset = HEADER_SIZE + i * SLOT_SIZE
                val nonce = blob.copyOfRange(offset, offset + Crypto.GCM_NONCE_BYTES)
                val sealed = blob.copyOfRange(offset + Crypto.GCM_NONCE_BYTES, offset + SLOT_SIZE)
                val payload = Crypto.gcmOpen(kek, nonce, sealed, header) ?: continue
                if (payload.size != PAYLOAD_SIZE) continue
                return Unlocked(
                    slotIndex = i,
                    vaultId = payload[0].toInt() and 0xFF,
                    wipe = (payload[1].toInt() and FLAG_WIPE) != 0,
                    masterKey = payload.copyOfRange(8, 8 + Crypto.KEY_BYTES),
                ).also { Crypto.wipe(payload) }
            }
            return null
        } finally {
            Crypto.wipe(kek)
        }
    }

    /**
     * Rewrite one slot in place, against the file's existing salt and cost.
     *
     * This is how a duress password gets changed without the main password being
     * involved: the old one identifies its own slot, and every other slot is left exactly
     * as it was. Only adding a first duress password needs the main password, because
     * nothing else can say which slot is safe to write into.
     */
    fun replaceSlot(
        blob: ByteArray,
        slotIndex: Int,
        password: ByteArray,
        vaultId: Int,
        wipe: Boolean,
        masterKey: ByteArray,
    ): ByteArray? {
        if (blob.size != FILE_SIZE || slotIndex !in 0 until SLOT_COUNT) return null
        val header = blob.copyOfRange(0, HEADER_SIZE)
        val memKiB = Crypto.getIntBE(blob, 5)
        val iterations = Crypto.getIntBE(blob, 9)
        val parallelism = blob[13].toInt() and 0xFF
        val salt = blob.copyOfRange(14, 14 + SALT_BYTES)

        val kek = Crypto.argon2id(password, salt, memKiB, iterations, parallelism)
        val payload = ByteArray(PAYLOAD_SIZE)
        payload[0] = vaultId.toByte()
        payload[1] = if (wipe) FLAG_WIPE.toByte() else 0
        System.arraycopy(Crypto.random(6), 0, payload, 2, 6)
        System.arraycopy(masterKey, 0, payload, 8, Crypto.KEY_BYTES)

        val nonce = Crypto.random(Crypto.GCM_NONCE_BYTES)
        val slot = nonce + Crypto.gcmSeal(kek, nonce, payload, header)
        Crypto.wipe(kek, payload)

        val out = blob.copyOf()
        System.arraycopy(slot, 0, out, HEADER_SIZE + slotIndex * SLOT_SIZE, SLOT_SIZE)
        return out
    }

    /** Replace a slot with random bytes, which is what an unused one looks like. */
    fun clearSlot(blob: ByteArray, slotIndex: Int): ByteArray {
        val out = blob.copyOf()
        if (slotIndex in 0 until SLOT_COUNT) {
            System.arraycopy(Crypto.random(SLOT_SIZE), 0, out, HEADER_SIZE + slotIndex * SLOT_SIZE, SLOT_SIZE)
        }
        return out
    }

    /**
     * Overwrite the slot that unlocked the real vault with random bytes.
     *
     * This is the duress wipe. It runs before any file is touched, because it is the
     * part that must be instant: once the wrapped master key is gone, the content is
     * unrecoverable whether or not the deletion afterwards ever finishes.
     */
    fun destroySlotsFor(blob: ByteArray, vaultId: Int, password: ByteArray): ByteArray {
        val out = blob.copyOf()
        val unlocked = unlock(blob, password) ?: return out
        if (unlocked.vaultId == vaultId) {
            System.arraycopy(Crypto.random(SLOT_SIZE), 0, out, HEADER_SIZE + unlocked.slotIndex * SLOT_SIZE, SLOT_SIZE)
        }
        Crypto.wipe(unlocked.masterKey)
        return out
    }

    private fun header(salt: ByteArray, memKiB: Int, iterations: Int, parallelism: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, header, 0, 4)
        header[4] = VERSION.toByte()
        Crypto.putIntBE(header, 5, memKiB)
        Crypto.putIntBE(header, 9, iterations)
        header[13] = parallelism.toByte()
        System.arraycopy(salt, 0, header, 14, SALT_BYTES)
        return header
    }

    private fun shuffle(slots: MutableList<ByteArray>) {
        for (i in slots.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = slots[i]
            slots[i] = slots[j]
            slots[j] = tmp
        }
    }
}
