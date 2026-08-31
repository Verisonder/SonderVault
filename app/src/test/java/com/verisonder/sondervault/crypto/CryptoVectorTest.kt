package com.verisonder.sondervault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Checks the Kotlin implementation against vectors produced by tools/reference.py.
 *
 * The point is not that the Kotlin passes its own tests — it is that two independent
 * implementations agree byte for byte. Where they disagree, one is wrong and this says
 * which value was expected.
 *
 * Regenerate with:  cd tools && python3 gen_vectors.py > vectors.json && python3 emit_test.py
 */
class CryptoVectorTest {

    private val FILE_KEY = ByteArray(32) { 0x11 }

    // ------------------------------------------------------------------ Argon2id

    @Test
    fun `argon2id matches the reference`() {
        val key = Crypto.argon2id(
            "correct horse battery staple".toByteArray(),
            hex("a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5"),
        )
        assertEquals("a9d70950800741bbbdd68da6a56581e69bc7ff3931ff54e99e34b34f16a222fd", key.toHex())
    }

    @Test
    fun `hkdf splits the file key as the reference does`() {
        assertEquals("d65bb7a58ea9357d6e1200169ba75dcd7254651d67163fea91aa8bea0e8d46ff", VaultFile.encryptionKey(FILE_KEY).toHex())
        assertEquals("8781177d15858f73fd5a95c15a71519a9cd96b9eb223d26561231fce1e1e3207", VaultFile.macKey(FILE_KEY).toHex())
    }

    // --------------------------------------------------------------- vault files

    @Test
    fun `container for empty matches the reference`() {
        val plain = pattern(0)
        val header = hex("534c4631010c97ec6099890f9cfd00000000000000004ca9b62ddd546142e0d80e597464153d0e4be013060649960793fb48be7a7813")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(54, blob.size)
        assertEquals("8edb4effa0c3723ee75628a93ed145eb454b60d269fd59feeb7f607b455c64b8", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `container for short matches the reference`() {
        val plain = pattern(100)
        val header = hex("534c4631010c97ec6099890f9cfd00000000000000648cf8b993b2b544f192503d6ca56389c58f1f7904473952e00dd9ace6e09738bf")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(186, blob.size)
        assertEquals("f2ab770bd05c4a6b333cdd0693bc523f83cc519f9247fa57691856f8cba0142c", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `container for exact_block matches the reference`() {
        val plain = pattern(4096)
        val header = hex("534c4631010c97ec6099890f9cfd0000000000001000b5268c99167de3c089c764db8f3581e1c14341638e0cc58c6a82b15e92251d5e")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(4182, blob.size)
        assertEquals("4bb25db9ffd2f8301969450dbcb2cc7ad9da61374b64a7663d9817ac81edbf4a", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `container for multi_block matches the reference`() {
        val plain = pattern(8525)
        val header = hex("534c4631010c97ec6099890f9cfd000000000000214d600d2068d677ca3e5abf76e18b545fbb44cb05b746903a1674b2d289eec1b637")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(8675, blob.size)
        assertEquals("8f24b59a5198afaf52051971e4845551bad6d2d81a000788248685c3d0d677a5", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `seeking to any offset returns what the reference says is there`() {
        val plain = pattern(8525)
        val blob = write(FILE_KEY, plain, blockLog2 = 12)
        VaultFileReader(ByteArraySource(blob), FILE_KEY).use { reader ->
            fun check(offset: Int, length: Int, expected: String) =
                assertEquals(
                    "read at $offset for $length",
                    expected,
                    sha256(reader.read(offset.toLong(), length)),
                )
            check(0, 16, "cd7d620a0588e54dd46e114a6f4ae5212c82e48abe5a13703649a745861a0c60")
            check(4095, 2, "b7d7de245bc54e7b0de8959197c09cfe6ee9827950f2cb3553894c5f4ee2ff5d")
            check(4096, 100, "5fb5d4b7ace49f5eac37422b8e1db12bab83cdbc2b7123abb61457e19c050d4c")
            check(8192, 333, "37c3b64cb69667e745de6e3215118caeeb6d059e208a27e02389c475aad47b55")
            check(5000, 4000, "c2acfe13f5ea7f1537b3f73566786be35b3a51967ee617ef7c406489a49ae623")
        }
    }

    @Test
    fun `a read past the end returns nothing`() {
        val plain = pattern(1000)
        val blob = write(FILE_KEY, plain)
        VaultFileReader(ByteArraySource(blob), FILE_KEY).use {
            assertEquals(0, it.read(1000, 10).size)
            assertEquals(10, it.read(990, 500).size)
        }
    }

    @Test
    fun `damage in one block does not stop another from being read`() {
        val plain = pattern(4096 * 3)
        val blob = write(FILE_KEY, plain, blockLog2 = 12)
        val stored = 4096 + 32
        blob[VaultFile.HEADER_SIZE + 2 * stored + 10] = (blob[VaultFile.HEADER_SIZE + 2 * stored + 10].toInt() xor 0xFF).toByte()

        VaultFileReader(ByteArraySource(blob), FILE_KEY).use { reader ->
            assertArrayEquals(plain.copyOfRange(0, 100), reader.read(0, 100))
            var refused = false
            try { reader.read(8192, 100) } catch (e: IOException) { refused = true }
            assertTrue("the damaged block must be refused", refused)
        }
    }

    @Test
    fun `a truncated container is refused`() {
        val blob = write(FILE_KEY, pattern(4096 * 2), blockLog2 = 12)
        var refused = false
        try { VaultFileReader(ByteArraySource(blob.copyOf(blob.size - 40)), FILE_KEY) }
        catch (e: IOException) { refused = true }
        assertTrue(refused)
    }

    @Test
    fun `editing the declared size is refused`() {
        val blob = write(FILE_KEY, pattern(1000))
        Crypto.putLongBE(blob, 14, 9999L)
        var refused = false
        try { VaultFileReader(ByteArraySource(blob), FILE_KEY) }
        catch (e: IOException) { refused = true }
        assertTrue(refused)
    }

    @Test
    fun `the wrong file key is refused`() {
        val blob = write(FILE_KEY, pattern(1000))
        var refused = false
        try { VaultFileReader(ByteArraySource(blob), ByteArray(32) { 0x22 }) }
        catch (e: IOException) { refused = true }
        assertTrue(refused)
    }

    // --------------------------------------------------------------- key slots

    @Test
    fun `the reference slot file opens exactly as the reference says`() {
        val blob = hex("534c4b3101000100000000000302dd1610d138ae7b4008c46ff516da0ad8590dd895886524f2f1b9527d414db0ebf9243c693ae7217fe90e397675d49f710d0936fda1ce4cb7194681f55fd9543f77d5d68967988e1ebf130a33d8310a3ca9faf2cb47ba30d99677dd8573dee5dabb2294329c52ff59239ac90ffcbf02f6b25bc4d2b1bd4ced0cf513a001b828a6f8cee456fd9ff5aa1f43259bef9057121220f6b0ea4054a87207db71c614671bd0928a321e2ccccb0659fd2c9ab16d3ffdfa6d6f9ffbecff12eaf38e8b5fd0f981f78646bfd22e949cf44b2697d8a22e8892a3aa4482373dc927297e57abe0c12f719bcd293e811952a2a45fc79de3364a9bbbcfccf51ea3b765b7b85d8ff84df238385f5a448503b2a9e945246a7a9fd6d2b6e6a9a935bcc8100b02114f2d5d")
        assertEquals(302, blob.size)
        assertEquals(KeySlots.FILE_SIZE, blob.size)

        val real = KeySlots.unlock(blob, "real-password".toByteArray())
        assertNotNull(real)
        assertEquals(KeySlots.VAULT_REAL, real!!.vaultId)
        assertFalse(real.wipe)
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", real.masterKey.toHex())

        val duress = KeySlots.unlock(blob, "duress-password".toByteArray())
        assertNotNull(duress)
        assertEquals(KeySlots.VAULT_DECOY, duress!!.vaultId)
        assertTrue(duress.wipe)
        assertEquals("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f", duress.masterKey.toHex())

        assertNull(KeySlots.unlock(blob, "not-a-password".toByteArray()))
    }

    @Test
    fun `a vault with no duress password looks the same as one with`() {
        val one = KeySlots.build(listOf(
            KeySlots.Entry("only".toByteArray(), KeySlots.VAULT_REAL, false, ByteArray(32) { 1 }),
        ))
        val two = KeySlots.build(listOf(
            KeySlots.Entry("real".toByteArray(), KeySlots.VAULT_REAL, false, ByteArray(32) { 1 }),
            KeySlots.Entry("duress".toByteArray(), KeySlots.VAULT_DECOY, true, ByteArray(32) { 2 }),
        ))
        assertEquals(KeySlots.FILE_SIZE, one.size)
        assertEquals(one.size, two.size)
    }

    @Test
    fun `editing the argon parameters is caught`() {
        val blob = hex("534c4b3101000100000000000302dd1610d138ae7b4008c46ff516da0ad8590dd895886524f2f1b9527d414db0ebf9243c693ae7217fe90e397675d49f710d0936fda1ce4cb7194681f55fd9543f77d5d68967988e1ebf130a33d8310a3ca9faf2cb47ba30d99677dd8573dee5dabb2294329c52ff59239ac90ffcbf02f6b25bc4d2b1bd4ced0cf513a001b828a6f8cee456fd9ff5aa1f43259bef9057121220f6b0ea4054a87207db71c614671bd0928a321e2ccccb0659fd2c9ab16d3ffdfa6d6f9ffbecff12eaf38e8b5fd0f981f78646bfd22e949cf44b2697d8a22e8892a3aa4482373dc927297e57abe0c12f719bcd293e811952a2a45fc79de3364a9bbbcfccf51ea3b765b7b85d8ff84df238385f5a448503b2a9e945246a7a9fd6d2b6e6a9a935bcc8100b02114f2d5d")
        blob[5] = (blob[5].toInt() xor 0x01).toByte()
        assertNull(KeySlots.unlock(blob, "real-password".toByteArray()))
    }

    @Test
    fun `the duress wipe removes the real slot and leaves the decoy`() {
        val realKey = ByteArray(32) { 7 }
        val decoyKey = ByteArray(32) { 9 }
        var blob = KeySlots.build(listOf(
            KeySlots.Entry("real".toByteArray(), KeySlots.VAULT_REAL, false, realKey),
            KeySlots.Entry("duress".toByteArray(), KeySlots.VAULT_DECOY, true, decoyKey),
        ))
        blob = KeySlots.destroySlotsFor(blob, KeySlots.VAULT_REAL, "real".toByteArray())
        assertNull("the real password must no longer open anything",
            KeySlots.unlock(blob, "real".toByteArray()))
        val decoy = KeySlots.unlock(blob, "duress".toByteArray())
        assertNotNull("the decoy must still open", decoy)
        assertArrayEquals(decoyKey, decoy!!.masterKey)
        assertEquals(KeySlots.FILE_SIZE, blob.size)
    }

    // ------------------------------------------------------------------ phrases

    /**
     * The shipped file, not a copy. A second copy under test resources could drift from
     * the one the app actually loads and the test would still pass.
     *
     * Both candidates are tried because the working directory of a unit test is the
     * module directory under Gradle and the repository root under some IDE run
     * configurations, and a test that fails on where it was started from is noise.
     */
    private val wordlistFile: File = listOf(
        File("src/main/res/raw/bip39_en.txt"),
        File("app/src/main/res/raw/bip39_en.txt"),
    ).firstOrNull { it.exists() } ?: error("wordlist not found from ${File(".").absolutePath}")

    private fun phrase() = RecoveryPhrase.from(wordlistFile.readLines().asSequence())

    @Test
    fun `the shipped wordlist is the canonical BIP-39 English list`() {
        assertEquals(RecoveryPhrase.WORDLIST_SHA256, sha256(wordlistFile.readBytes()))
        assertEquals(RecoveryPhrase.WORDLIST_SIZE, wordlistFile.readLines().count { it.isNotBlank() })
    }

    @Test
    fun `phrase derivation matches the reference`() {
        val words = listOf("abandon", "zoo", "legal", "winner", "thank", "yellow")
        val key = RecoveryPhrase.toKey(words, hex("a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5"))
        assertEquals("6ee287c18fb17edd9cc1ddcd64bb2bfce9ecd0c0a4ee6f5ac3e59a4b1487c09d", key.toHex())
    }

    @Test
    fun `typed input is parsed and corrected on the four letter prefix`() {
        val p = phrase()
        assertEquals(listOf("abandon"), p.parse("abanmisspelled"))
        assertEquals(
            listOf("abandon", "zoo", "legal", "winner", "thank", "yellow"),
            p.parse("  ABANdon   zoo, legal-winner thank YELLOW ")
        )
        assertNull(p.parse("zzzz qqqq"))
        assertNull(p.parse(""))
    }

    @Test
    fun `generated phrases are six words and differ`() {
        val p = phrase()
        val a = p.generate()
        assertEquals(RecoveryPhrase.WORD_COUNT, a.size)
        assertTrue(a.all { p.contains(it) })
        assertFalse(a == p.generate() && a == p.generate())
    }

    // ------------------------------------------------------------------ helpers

    private fun pattern(size: Int) = ByteArray(size) { ((it * 37 + 11) and 0xFF).toByte() }

    private fun write(key: ByteArray, plain: ByteArray, blockLog2: Int = 12): ByteArray {
        val file = File.createTempFile("slf", null).apply { deleteOnExit() }
        VaultFileWriter(file, key, blockLog2).use { it.write(plain) }
        return file.readBytes()
    }

    private fun readAll(key: ByteArray, blob: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        VaultFileReader(ByteArraySource(blob), key).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /**
     * The reference containers were produced with a fixed nonce. The writer picks its
     * own, so to compare byte for byte the reference header is spliced in and the
     * payload rebuilt from it.
     */
    private fun encryptWithHeaderNonce(
        key: ByteArray,
        plain: ByteArray,
        referenceHeader: ByteArray,
    ): ByteArray {
        val nonce = referenceHeader.copyOfRange(6, 14)
        val blockLog2 = referenceHeader[5].toInt()
        val blockSize = 1 shl blockLog2
        val encKey = VaultFile.encryptionKey(key)
        val macKey = VaultFile.macKey(key)
        val out = ByteArrayOutputStream()
        val prefix = VaultFile.prefix(blockLog2, nonce, plain.size.toLong())
        out.write(prefix)
        out.write(Crypto.hmac(macKey, prefix))
        var index = 0L
        var offset = 0
        while (offset < plain.size) {
            val chunk = plain.copyOfRange(offset, minOf(offset + blockSize, plain.size))
            val ct = Crypto.ctr(encKey, nonce, index * (blockSize / 16), chunk)
            out.write(ct)
            out.write(VaultFile.blockMac(macKey, nonce, index, ct, ct.size))
            offset += blockSize
            index++
        }
        return out.toByteArray()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
