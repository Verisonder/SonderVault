package com.verisonder.sondervault.bundle

import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.crypto.RecoveryPhrase
import com.verisonder.sondervault.vault.Vault
import com.verisonder.sondervault.vault.VaultItem
import com.verisonder.sondervault.vault.VaultStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The cheapest Argon2 settings the library accepts. The derivation itself is checked
 * against the reference vectors elsewhere; what is under test here is the container.
 */
private const val MEM = 8
private const val ITERS = 1
private const val PAR = 1

class BundleTest {

    private lateinit var base: File
    private lateinit var vault: Vault
    private val phrase = listOf("abandon", "zoo", "legal", "winner", "thank", "yellow")

    @Before
    fun setUp() {
        base = Files.createTempDirectory("bundle").toFile().apply { deleteOnExit() }
        vault = VaultStore(base, MEM, ITERS, PAR)
            .configure("main".toByteArray(), null, false).real
    }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun add(name: String, size: Int, seed: Int): Pair<VaultItem, ByteArray> {
        val content = bytes(size, seed)
        val item = vault.importItem(name, "image/jpeg", ByteArrayInputStream(content))
        return item to content
    }

    private fun target(name: String = "out.sondervault") = File(base, name)

    private class Result(val file: File, val entries: Int)

    private fun write(items: List<VaultItem>, to: File = target()): Result {
        val written = to.outputStream().use {
            BundleWriter.write(vault, items, phrase, it, MEM, ITERS, PAR)
        }
        return Result(to, written.entries)
    }

    private fun open(file: File, words: List<String> = phrase) =
        BundleReader.open(file, words)

    private fun readOut(reader: BundleReader, entry: BundleEntry): ByteArray =
        reader.open(entry).use { r -> ByteArrayOutputStream().also { r.copyTo(it) }.toByteArray() }

    // ---------------------------------------------------------------- round trips

    @Test
    fun `a bundle round trips`() {
        val (a, aBytes) = add("one.jpg", 4000, 1)
        val (b, bBytes) = add("two.jpg", 9000, 2)
        val written = write(listOf(a, b))

        assertEquals(2, written.entries)
        assertTrue(written.file.length() > 13000)

        val reader = open(written.file)
        assertEquals(listOf("one.jpg", "two.jpg"), reader.entries.map { it.name })
        assertArrayEquals(aBytes, readOut(reader, reader.entries[0]))
        assertArrayEquals(bBytes, readOut(reader, reader.entries[1]))
    }

    @Test
    fun `entry metadata survives`() {
        val item = vault.importItem(
            "holiday.jpg", "image/jpeg", ByteArrayInputStream(bytes(500, 3)), capturedAt = 1234567L,
        )
        val reader = open(write(listOf(item)).file)
        val entry = reader.entries.single()
        assertEquals("holiday.jpg", entry.name)
        assertEquals("image/jpeg", entry.mimeType)
        assertEquals(500L, entry.size)
        assertEquals(1234567L, entry.capturedAt)
    }

    @Test
    fun `a filename with tabs and newlines survives`() {
        val item = vault.importItem(
            "a\tname\nwith\\separators.jpg", "image/jpeg", ByteArrayInputStream(bytes(100, 4)),
        )
        val reader = open(write(listOf(item)).file)
        assertEquals("a\tname\nwith\\separators.jpg", reader.entries.single().name)
    }

    @Test
    fun `a large item round trips across block boundaries`() {
        val (item, content) = add("big.bin", 3 * 1024 * 1024 + 77, 5)
        val reader = open(write(listOf(item)).file)
        assertArrayEquals(content, readOut(reader, reader.entries.single()))
    }

    @Test
    fun `extracting into a vault reproduces the contents`() {
        val (a, aBytes) = add("one.jpg", 2000, 6)
        val (b, bBytes) = add("two.jpg", 3000, 7)
        val written = write(listOf(a, b))

        val other = Files.createTempDirectory("restore").toFile().apply { deleteOnExit() }
        val restored = VaultStore(other, MEM, ITERS, PAR)
            .configure("other".toByteArray(), null, false).real

        assertEquals(2, open(written.file).extractInto(restored))
        val items = restored.items().sortedBy { it.name }
        assertEquals(listOf("one.jpg", "two.jpg"), items.map { it.name })

        fun content(item: VaultItem) = restored.open(item).use { r ->
            ByteArrayOutputStream().also { r.copyTo(it) }.toByteArray()
        }
        assertArrayEquals(aBytes, content(items[0]))
        assertArrayEquals(bBytes, content(items[1]))
    }

    @Test
    fun `a restored item does not share a key with the bundle`() {
        val (item, _) = add("one.jpg", 1000, 8)
        val written = write(listOf(item))

        val other = Files.createTempDirectory("restore2").toFile().apply { deleteOnExit() }
        val restored = VaultStore(other, MEM, ITERS, PAR)
            .configure("other".toByteArray(), null, false).real
        open(written.file).extractInto(restored)

        val bundleKey = open(written.file).entries.single().fileKey
        val restoredKey = restored.items().single().fileKey
        assertFalse("a leaked bundle must not expose the restored copy",
            bundleKey.contentEquals(restoredKey))
    }

    // ------------------------------------------------------------------- refusals

    @Test
    fun `the wrong phrase is refused`() {
        val (item, _) = add("one.jpg", 500, 9)
        val written = write(listOf(item))
        var refused = false
        try {
            open(written.file, listOf("zoo", "zoo", "zoo", "zoo", "zoo", "zoo"))
        } catch (e: BundleReader.Companion.WrongPhrase) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun `a phrase in the wrong order is refused`() {
        val (item, _) = add("one.jpg", 500, 10)
        val written = write(listOf(item))
        var refused = false
        try {
            open(written.file, phrase.reversed())
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun `editing the argon parameters is refused`() {
        val (item, _) = add("one.jpg", 500, 11)
        val written = write(listOf(item))
        val raw = written.file.readBytes()
        // the header is associated data, so lowering the cost breaks the seal
        raw[12] = (raw[12].toInt() xor 0x01).toByte()
        val edited = File(base, "edited.sondervault").apply { writeBytes(raw) }

        var refused = false
        try {
            open(edited)
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun `a tampered entry is caught when it is read`() {
        val (item, _) = add("one.jpg", 4000, 12)
        val written = write(listOf(item))
        val raw = written.file.readBytes()
        raw[raw.size - 200] = (raw[raw.size - 200].toInt() xor 0xFF).toByte()
        val edited = File(base, "tampered.sondervault").apply { writeBytes(raw) }

        val reader = open(edited)
        var refused = false
        try {
            readOut(reader, reader.entries.single())
        } catch (e: IOException) {
            refused = true
        }
        assertTrue("the per-block MAC must catch this", refused)
    }

    @Test
    fun `a truncated bundle is refused`() {
        val (item, _) = add("one.jpg", 4000, 13)
        val written = write(listOf(item))
        val raw = written.file.readBytes()
        val cut = File(base, "cut.sondervault").apply { writeBytes(raw.copyOf(raw.size - 500)) }

        var refused = false
        try {
            open(cut)
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun `an unrelated file is refused`() {
        val junk = File(base, "junk.sondervault").apply { writeBytes(bytes(2000, 14)) }
        var refused = false
        try {
            open(junk)
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }

    // --------------------------------------------------------------------- phrases

    @Test
    fun `each bundle gets a different phrase in practice`() {
        val words = RecoveryPhrase.from(wordlist().readLines().asSequence())
        val a = words.generate()
        val b = words.generate()
        assertNotEquals(a, b)
    }

    @Test
    fun `the same phrase and salt derive the same key`() {
        val salt = Crypto.random(16)
        assertArrayEquals(
            RecoveryPhrase.toKey(phrase, salt, MEM, ITERS, PAR),
            RecoveryPhrase.toKey(phrase, salt, MEM, ITERS, PAR),
        )
    }

    private fun wordlist(): File = listOf(
        File("src/main/res/raw/bip39_en.txt"),
        File("app/src/main/res/raw/bip39_en.txt"),
    ).first { it.exists() }
}
