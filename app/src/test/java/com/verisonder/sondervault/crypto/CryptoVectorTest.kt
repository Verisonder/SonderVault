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
 * Regenerate with:  cd tools && python3 gen_vectors.py > ../app/src/test/resources/vectors.json
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
        assertEquals("77ccf00be602d75ac00f0f153e248a1553363eec3df6431a755126b9d32d4c6d", VaultFile.encryptionKey(FILE_KEY).toHex())
        assertEquals("6360ea63b267dd4d14ef7ff3cddb7b24bc67da5190e24f093ed4ea0dce8917ca", VaultFile.macKey(FILE_KEY).toHex())
    }

    // --------------------------------------------------------------- vault files

    @Test
    fun `container for empty matches the reference`() {
        val plain = pattern(0)
        val header = hex("53564631010cb84770c44de81ade0000000000000000afa309aed586342588050a21e21f514d4e0d2de886a51b638164c25ee031a31d")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(54, blob.size)
        assertEquals("c18b4ebde7b7c3b1af0949279f7590d48b448aa877cff8d8bdfac6f0e55eea84", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `container for short matches the reference`() {
        val plain = pattern(100)
        val header = hex("53564631010cb84770c44de81ade00000000000000646ef4964cb96a7d237b00429326d4ab4e78ef3c7ee1c77c58030be5da25489fdb")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(186, blob.size)
        assertEquals("40b1beb771c111d84848d444de112751a284984b5968df2c7490d9269ad39d3c", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `container for exact_block matches the reference`() {
        val plain = pattern(4096)
        val header = hex("53564631010cb84770c44de81ade0000000000001000c8f4267d69f6e41555544f22862bcb4afd40e3e231bba0702089cfc25f288f8e")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(4182, blob.size)
        assertEquals("a4a9f9c27b5be07540690796456e7006e323ebda69ca187d428c2a0b8ae3c423", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }

    @Test
    fun `container for multi_block matches the reference`() {
        val plain = pattern(8525)
        val header = hex("53564631010cb84770c44de81ade000000000000214dfa46319390eeb042e3e678c41381bbd5d70727a8c6637365072be61fc6ad5b1b")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals(8675, blob.size)
        assertEquals("4c1837fe257cfd0cf679e9a517935fe6da0d50e6b8e97d7fd4a9be62c09cb360", sha256(blob))
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
        val blob = hex("53564b3101000100000000000302fc65404e756613289d647538ad4caf997a5ecc409c507afb50ead3c717cd01ebbde6c0d045060601efec0f7d11e19ea357fb3f74fa703e1607c02fbce3371a4054b8d5da0d1418ffab3e361f7d36b30c3aa9f860d69406a95ae2b1537162a3c60192c3279fda6d2226355d56b91512d84c3f75d39436fed105621d0a43d3cb18d53d522a8b7daffc51d3078b1d46adeac39417fbb602bf00f7ff313cd9a3b0f36fde80e78583d7a111551f5160fa5077b91c332b94998512dc0addd5de1df3bd93e2f80bb80c7401a678abb7b34133f197678e594d37bb4b57cff16705bca4f1cf5baf5ab970f90680515831dbfeda091fa1c659e5a38dd8b396bdb2c510bce72a81db0a740d28feff8a3bd4c0cf6850831a76d897aa2c4507fe1f544c723d34")
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
        val blob = hex("53564b3101000100000000000302fc65404e756613289d647538ad4caf997a5ecc409c507afb50ead3c717cd01ebbde6c0d045060601efec0f7d11e19ea357fb3f74fa703e1607c02fbce3371a4054b8d5da0d1418ffab3e361f7d36b30c3aa9f860d69406a95ae2b1537162a3c60192c3279fda6d2226355d56b91512d84c3f75d39436fed105621d0a43d3cb18d53d522a8b7daffc51d3078b1d46adeac39417fbb602bf00f7ff313cd9a3b0f36fde80e78583d7a111551f5160fa5077b91c332b94998512dc0addd5de1df3bd93e2f80bb80c7401a678abb7b34133f197678e594d37bb4b57cff16705bca4f1cf5baf5ab970f90680515831dbfeda091fa1c659e5a38dd8b396bdb2c510bce72a81db0a740d28feff8a3bd4c0cf6850831a76d897aa2c4507fe1f544c723d34")
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
     * The shipped file, not a copy. Both candidates are tried because the working
     * directory of a unit test is the module directory under Gradle and the repository
     * root under some IDE run configurations.
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
