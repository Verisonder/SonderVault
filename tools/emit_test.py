import json

v = json.load(open("vectors.json"))

s = v["slots"]
c = {x["password"]: x for x in s["cases"]}
seeks = v["vaultfile_seeks"]
reads = "\n".join(
    f'        check({r["offset"]}, {r["length"]}, "{r["expectedSha256"]}")'
    for r in seeks["reads"]
)

cases = "\n".join(
    f'''    @Test
    fun `container for {name} matches the reference`() {{
        val plain = pattern({v[f"vaultfile_{name}"]["plainSize"]})
        val header = hex("{v[f"vaultfile_{name}"]["header"]}")
        val blob = encryptWithHeaderNonce(FILE_KEY, plain, header)
        assertEquals({v[f"vaultfile_{name}"]["containerSize"]}, blob.size)
        assertEquals("{v[f"vaultfile_{name}"]["containerSha256"]}", sha256(blob))
        assertArrayEquals(plain, readAll(FILE_KEY, blob))
    }}
'''
    for name in ["empty", "short", "exact_block", "multi_block"]
)

out = f'''package com.verisonder.sonderlock.crypto

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
class CryptoVectorTest {{

    private val FILE_KEY = ByteArray(32) {{ 0x11 }}

    // ------------------------------------------------------------------ Argon2id

    @Test
    fun `argon2id matches the reference`() {{
        val key = Crypto.argon2id(
            "{v["argon2id"]["password"]}".toByteArray(),
            hex("{v["argon2id"]["salt"]}"),
        )
        assertEquals("{v["argon2id"]["expected"]}", key.toHex())
    }}

    @Test
    fun `hkdf splits the file key as the reference does`() {{
        assertEquals("{v["hkdf"]["encKey"]}", VaultFile.encryptionKey(FILE_KEY).toHex())
        assertEquals("{v["hkdf"]["macKey"]}", VaultFile.macKey(FILE_KEY).toHex())
    }}

    // --------------------------------------------------------------- vault files

{cases}
    @Test
    fun `seeking to any offset returns what the reference says is there`() {{
        val plain = pattern({4096 * 2 + 333})
        val blob = write(FILE_KEY, plain, blockLog2 = 12)
        VaultFileReader(ByteArraySource(blob), FILE_KEY).use {{ reader ->
            fun check(offset: Int, length: Int, expected: String) =
                assertEquals(
                    "read at $offset for $length",
                    expected,
                    sha256(reader.read(offset.toLong(), length)),
                )
{reads}
        }}
    }}

    @Test
    fun `a read past the end returns nothing`() {{
        val plain = pattern(1000)
        val blob = write(FILE_KEY, plain)
        VaultFileReader(ByteArraySource(blob), FILE_KEY).use {{
            assertEquals(0, it.read(1000, 10).size)
            assertEquals(10, it.read(990, 500).size)
        }}
    }}

    @Test
    fun `damage in one block does not stop another from being read`() {{
        val plain = pattern(4096 * 3)
        val blob = write(FILE_KEY, plain, blockLog2 = 12)
        val stored = 4096 + 32
        blob[VaultFile.HEADER_SIZE + 2 * stored + 10] = (blob[VaultFile.HEADER_SIZE + 2 * stored + 10].toInt() xor 0xFF).toByte()

        VaultFileReader(ByteArraySource(blob), FILE_KEY).use {{ reader ->
            assertArrayEquals(plain.copyOfRange(0, 100), reader.read(0, 100))
            var refused = false
            try {{ reader.read(8192, 100) }} catch (e: IOException) {{ refused = true }}
            assertTrue("the damaged block must be refused", refused)
        }}
    }}

    @Test
    fun `a truncated container is refused`() {{
        val blob = write(FILE_KEY, pattern(4096 * 2), blockLog2 = 12)
        var refused = false
        try {{ VaultFileReader(ByteArraySource(blob.copyOf(blob.size - 40)), FILE_KEY) }}
        catch (e: IOException) {{ refused = true }}
        assertTrue(refused)
    }}

    @Test
    fun `editing the declared size is refused`() {{
        val blob = write(FILE_KEY, pattern(1000))
        Crypto.putLongBE(blob, 14, 9999L)
        var refused = false
        try {{ VaultFileReader(ByteArraySource(blob), FILE_KEY) }}
        catch (e: IOException) {{ refused = true }}
        assertTrue(refused)
    }}

    @Test
    fun `the wrong file key is refused`() {{
        val blob = write(FILE_KEY, pattern(1000))
        var refused = false
        try {{ VaultFileReader(ByteArraySource(blob), ByteArray(32) {{ 0x22 }}) }}
        catch (e: IOException) {{ refused = true }}
        assertTrue(refused)
    }}

    // --------------------------------------------------------------- key slots

    @Test
    fun `the reference slot file opens exactly as the reference says`() {{
        val blob = hex("{s["blob"]}")
        assertEquals({s["size"]}, blob.size)
        assertEquals(KeySlots.FILE_SIZE, blob.size)

        val real = KeySlots.unlock(blob, "{[x for x in s["cases"] if x.get("vaultId") == 0][0]["password"]}".toByteArray())
        assertNotNull(real)
        assertEquals(KeySlots.VAULT_REAL, real!!.vaultId)
        assertFalse(real.wipe)
        assertEquals("{c["real-password"]["masterKey"]}", real.masterKey.toHex())

        val duress = KeySlots.unlock(blob, "duress-password".toByteArray())
        assertNotNull(duress)
        assertEquals(KeySlots.VAULT_DECOY, duress!!.vaultId)
        assertTrue(duress.wipe)
        assertEquals("{c["duress-password"]["masterKey"]}", duress.masterKey.toHex())

        assertNull(KeySlots.unlock(blob, "not-a-password".toByteArray()))
    }}

    @Test
    fun `a vault with no duress password looks the same as one with`() {{
        val one = KeySlots.build(listOf(
            KeySlots.Entry("only".toByteArray(), KeySlots.VAULT_REAL, false, ByteArray(32) {{ 1 }}),
        ))
        val two = KeySlots.build(listOf(
            KeySlots.Entry("real".toByteArray(), KeySlots.VAULT_REAL, false, ByteArray(32) {{ 1 }}),
            KeySlots.Entry("duress".toByteArray(), KeySlots.VAULT_DECOY, true, ByteArray(32) {{ 2 }}),
        ))
        assertEquals(KeySlots.FILE_SIZE, one.size)
        assertEquals(one.size, two.size)
    }}

    @Test
    fun `editing the argon parameters is caught`() {{
        val blob = hex("{s["blob"]}")
        blob[5] = (blob[5].toInt() xor 0x01).toByte()
        assertNull(KeySlots.unlock(blob, "real-password".toByteArray()))
    }}

    @Test
    fun `the duress wipe removes the real slot and leaves the decoy`() {{
        val realKey = ByteArray(32) {{ 7 }}
        val decoyKey = ByteArray(32) {{ 9 }}
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
    }}

    // ------------------------------------------------------------------ phrases

    private fun phrase() = RecoveryPhrase.from(
        javaClass.getResourceAsStream("/bip39_en.txt")!!.bufferedReader().lineSequence()
    )

    @Test
    fun `the wordlist is the canonical BIP-39 English list`() {{
        val bytes = javaClass.getResourceAsStream("/bip39_en.txt")!!.readBytes()
        assertEquals(RecoveryPhrase.WORDLIST_SHA256, sha256(bytes))
    }}

    @Test
    fun `phrase derivation matches the reference`() {{
        val words = listOf({", ".join(f'"{w}"' for w in v["phrase"]["words"])})
        val key = RecoveryPhrase.toKey(words, hex("{v["phrase"]["salt"]}"))
        assertEquals("{v["phrase"]["key"]}", key.toHex())
    }}

    @Test
    fun `typed input is parsed and corrected on the four letter prefix`() {{
        val p = phrase()
        assertEquals(listOf("abandon"), p.parse("abanmisspelled"))
        assertEquals(
            listOf({", ".join(f'"{w}"' for w in v["phrase"]["words"])}),
            p.parse("  ABANdon   zoo, legal-winner thank YELLOW ")
        )
        assertNull(p.parse("zzzz qqqq"))
        assertNull(p.parse(""))
    }}

    @Test
    fun `generated phrases are six words and differ`() {{
        val p = phrase()
        val a = p.generate()
        assertEquals(RecoveryPhrase.WORD_COUNT, a.size)
        assertTrue(a.all {{ p.contains(it) }})
        assertFalse(a == p.generate() && a == p.generate())
    }}

    // ------------------------------------------------------------------ helpers

    private fun pattern(size: Int) = ByteArray(size) {{ ((it * 37 + 11) and 0xFF).toByte() }}

    private fun write(key: ByteArray, plain: ByteArray, blockLog2: Int = 12): ByteArray {{
        val file = File.createTempFile("slf", null).apply {{ deleteOnExit() }}
        VaultFileWriter(file, key, blockLog2).use {{ it.write(plain) }}
        return file.readBytes()
    }}

    private fun readAll(key: ByteArray, blob: ByteArray): ByteArray {{
        val out = ByteArrayOutputStream()
        VaultFileReader(ByteArraySource(blob), key).use {{ it.copyTo(out) }}
        return out.toByteArray()
    }}

    /**
     * The reference containers were produced with a fixed nonce. The writer picks its
     * own, so to compare byte for byte the reference header is spliced in and the
     * payload rebuilt from it.
     */
    private fun encryptWithHeaderNonce(
        key: ByteArray,
        plain: ByteArray,
        referenceHeader: ByteArray,
        reuse: Boolean = false,
    ): ByteArray {{
        if (reuse || referenceHeader.size < VaultFile.HEADER_SIZE) return write(key, plain)
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
        while (offset < plain.size) {{
            val chunk = plain.copyOfRange(offset, minOf(offset + blockSize, plain.size))
            val ct = Crypto.ctr(encKey, nonce, index * (blockSize / 16), chunk)
            out.write(ct)
            out.write(VaultFile.blockMac(macKey, nonce, index, ct, ct.size))
            offset += blockSize
            index++
        }}
        return out.toByteArray()
    }}

    private fun hex(s: String) = ByteArray(s.length / 2) {{
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }}

    private fun ByteArray.toHex() = joinToString("") {{ "%02x".format(it) }}

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {{ "%02x".format(it) }}
}}
'''

open("CryptoVectorTest.kt", "w").write(out)
print("written", len(out), "bytes")
