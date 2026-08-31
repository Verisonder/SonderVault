package com.verisonder.sondervault.vault

import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.crypto.KeySlots
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Argon2id at 64 MiB is deliberately slow, so these use the smallest parameters the
 * library accepts. Nothing here is testing the key derivation — that is covered against
 * the reference vectors in CryptoVectorTest — only what is built on top of it.
 */
class VaultTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("sondervault").toFile()
        base.deleteOnExit()
    }

    private fun store() = VaultStore(base, argonMemKiB = 8, argonIterations = 1, argonParallelism = 1)

    private fun storeAt(dir: File) =
        VaultStore(dir, argonMemKiB = 8, argonIterations = 1, argonParallelism = 1)

    private fun bytes(size: Int, seed: Int = 0) =
        ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun add(vault: Vault, name: String, size: Int, seed: Int = 0): VaultItem =
        vault.importItem(name, "image/jpeg", ByteArrayInputStream(bytes(size, seed)))

    private fun readBack(vault: Vault, item: VaultItem): ByteArray =
        vault.open(item).use { reader ->
            ByteArrayOutputStream().also { reader.copyTo(it) }.toByteArray()
        }

    // ------------------------------------------------------------------- the index

    @Test
    fun `index survives a round trip`() {
        val items = listOf(
            VaultItem("a1", "holiday.jpg", "image/jpeg", 1234, 100, 200, ByteArray(32) { 1 }, true),
            VaultItem("b2", "clip.mp4", "video/mp4", 999999999L, 300, 400, ByteArray(32) { 2 }, false),
        )
        assertEquals(items, VaultIndex.parse(VaultIndex.serialise(items)))
    }

    @Test
    fun `a filename holding tabs and newlines survives`() {
        val awkward = VaultItem(
            "c3", "a\tname\nwith\\separators.jpg", "image/jpeg", 1, 2, 3, ByteArray(32), false,
        )
        val parsed = VaultIndex.parse(VaultIndex.serialise(listOf(awkward)))
        assertEquals(1, parsed.size)
        assertEquals(awkward.name, parsed[0].name)
    }

    @Test
    fun `an empty index round trips`() {
        assertEquals(emptyList<VaultItem>(), VaultIndex.parse(VaultIndex.serialise(emptyList())))
    }

    @Test
    fun `item ids do not repeat`() {
        val ids = (1..500).map { VaultIndex.newItemId() }.toSet()
        assertEquals(500, ids.size)
    }

    // -------------------------------------------------------------------- contents

    @Test
    fun `an imported file reads back byte for byte`() {
        val configured = store().configure("main".toByteArray(), null, false)
        val original = bytes(5000, seed = 7)
        val item = configured.real.importItem(
            "photo.jpg", "image/jpeg", ByteArrayInputStream(original),
        )
        assertEquals(original.size.toLong(), item.size)
        assertArrayEquals(original, readBack(configured.real, item))
    }

    @Test
    fun `nothing readable is left lying on disk`() {
        val configured = store().configure("main".toByteArray(), null, false)
        val marker = "SECRET-MARKER-STRING".toByteArray()
        configured.real.importItem("note.txt", "text/plain", ByteArrayInputStream(marker))

        val everything = base.walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList()
        assertTrue("something was written", everything.isNotEmpty())
        for (blob in everything) {
            assertFalse(
                "plaintext found on disk",
                String(blob, Charsets.ISO_8859_1).contains("SECRET-MARKER"),
            )
        }
        // the filename is in the index, which is encrypted too
        for (blob in everything) {
            assertFalse(String(blob, Charsets.ISO_8859_1).contains("note.txt"))
        }
    }

    @Test
    fun `items survive being reopened`() {
        val configured = store().configure("main".toByteArray(), null, false)
        add(configured.real, "one.jpg", 100, 1)
        add(configured.real, "two.jpg", 200, 2)
        configured.real.close()

        val opened = store().unlock("main".toByteArray())
        assertNotNull(opened)
        assertEquals(setOf("one.jpg", "two.jpg"), opened!!.vault.items().map { it.name }.toSet())
    }

    @Test
    fun `deleting an item removes its content and its record`() {
        val configured = store().configure("main".toByteArray(), null, false)
        val keep = add(configured.real, "keep.jpg", 100, 1)
        val drop = add(configured.real, "drop.jpg", 4000, 2)
        val before = configured.real.sizeOnDisk()

        configured.real.delete(drop)

        assertEquals(listOf(keep.id), configured.real.items().map { it.id })
        assertTrue("the bytes should be gone too", configured.real.sizeOnDisk() < before - 3000)
    }

    @Test
    fun `a thumbnail is stored separately and reads back`() {
        val configured = store().configure("main".toByteArray(), null, false)
        val thumb = bytes(300, seed = 9)
        val item = configured.real.importItem(
            "photo.jpg", "image/jpeg", ByteArrayInputStream(bytes(2000)), thumbnail = thumb,
        )
        assertTrue(item.hasThumbnail)
        assertArrayEquals(thumb, configured.real.readThumbnail(item))
    }

    @Test
    fun `an item with no thumbnail reports none`() {
        val configured = store().configure("main".toByteArray(), null, false)
        val item = add(configured.real, "photo.jpg", 100)
        assertFalse(item.hasThumbnail)
        assertNull(configured.real.readThumbnail(item))
    }

    // ---------------------------------------------------------------------- unlock

    @Test
    fun `the wrong password opens nothing`() {
        store().configure("main".toByteArray(), null, false)
        assertNull(store().unlock("wrong".toByteArray()))
    }

    @Test
    fun `the two vaults live in different directories`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        assertNotNull(configured.decoy)
        assertNotEquals(configured.real.directory.name, requireNotNull(configured.decoy).directory.name)
    }

    // ----------------------------------------------------------------------- decoy

    @Test
    fun `the duress password opens the decoy and leaves the real vault alone`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        val decoy = requireNotNull(configured.decoy)
        add(configured.real, "private.jpg", 3000, 1)
        add(decoy, "ordinary.jpg", 500, 2)
        configured.real.close()
        decoy.close()

        val duressOpen = store().unlock("duress".toByteArray())!!
        assertTrue(duressOpen.isDecoy)
        assertFalse("no wipe was configured", duressOpen.wiped)
        assertEquals(listOf("ordinary.jpg"), duressOpen.vault.items().map { it.name })

        val mainOpen = store().unlock("main".toByteArray())!!
        assertFalse(mainOpen.isDecoy)
        assertEquals(listOf("private.jpg"), mainOpen.vault.items().map { it.name })
    }

    @Test
    fun `a non-wiping duress password can be used more than once`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        add(configured.real, "private.jpg", 3000, 1)
        add(requireNotNull(configured.decoy), "ordinary.jpg", 500, 2)

        repeat(3) {
            val opened = store().unlock("duress".toByteArray())!!
            assertEquals(listOf("ordinary.jpg"), opened.vault.items().map { it.name })
        }
        assertEquals(listOf("private.jpg"), store().unlock("main".toByteArray())!!.vault.items().map { it.name })
    }

    // ------------------------------------------------------------------ the wipe

    @Test
    fun `a wiping duress password destroys the real vault and keeps the decoy`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), true)
        val decoy = requireNotNull(configured.decoy)
        add(configured.real, "private.jpg", 40000, 1)
        add(configured.real, "also-private.mp4", 60000, 2)
        add(decoy, "ordinary.jpg", 500, 3)
        val realDirectory = configured.real.directory
        configured.real.close()
        decoy.close()

        val opened = store().unlock("duress".toByteArray())!!
        assertTrue(opened.wiped)
        assertTrue(opened.isDecoy)
        assertEquals(listOf("ordinary.jpg"), opened.vault.items().map { it.name })
        assertFalse("the real vault directory should be gone", realDirectory.exists())
    }

    @Test
    fun `after a wipe the real password still opens something, and it is empty`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), true)
        add(configured.real, "private.jpg", 5000, 1)
        add(requireNotNull(configured.decoy), "ordinary.jpg", 500, 2)

        store().unlock("duress".toByteArray())

        // failing outright would itself say that something was taken away
        val after = store().unlock("main".toByteArray())
        assertNotNull("the real password must not start failing", after)
        assertFalse(after!!.isDecoy)
        assertEquals(emptyList<VaultItem>(), after.vault.items())
    }

    @Test
    fun `a wipe leaves the decoy contents readable`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), true)
        val decoy = requireNotNull(configured.decoy)
        add(configured.real, "private.jpg", 5000, 1)
        val decoyItem = add(decoy, "ordinary.jpg", 800, 2)
        val expected = readBack(decoy, decoyItem)

        val opened = store().unlock("duress".toByteArray())!!
        val recovered = opened.vault.items().single()
        assertArrayEquals(expected, readBack(opened.vault, recovered))
    }

    @Test
    fun `a wipe takes the storage figure down with it`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), true)
        add(configured.real, "big.mp4", 200_000, 1)
        add(requireNotNull(configured.decoy), "small.jpg", 1_000, 2)
        val before = store().totalSizeOnDisk()

        store().unlock("duress".toByteArray())

        val after = store().totalSizeOnDisk()
        assertTrue("size should fall sharply, was $before now $after", after < before / 10)
    }

    // ------------------------------------------------------------- indistinguishable

    @Test
    fun `a vault with no duress password has the same slot file as one with`() {
        val withDuress = Files.createTempDirectory("with").toFile()
        val without = Files.createTempDirectory("without").toFile()
        storeAt(withDuress).configure("main".toByteArray(), "duress".toByteArray(), true)
        storeAt(without).configure("main".toByteArray(), null, false)

        val a = File(withDuress, "slots.bin").readBytes()
        val b = File(without, "slots.bin").readBytes()
        assertEquals(KeySlots.FILE_SIZE, a.size)
        assertEquals(a.size, b.size)
    }

    @Test
    fun `directory names give nothing away`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), true)
        for (vault in listOfNotNull<Vault>(configured.real, configured.decoy)) {
            val name = vault.directory.name
            assertEquals("20 hex characters and nothing else", 20, name.length)
            assertTrue(name.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `master keys differ between the two vaults`() {
        val a = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        val realDir = a.real.directory.name
        val decoyDir = requireNotNull(a.decoy).directory.name
        assertNotEquals(realDir, decoyDir)
        // and a second install shares nothing with the first
        val other = storeAt(Files.createTempDirectory("other").toFile())
            .configure("main".toByteArray(), "duress".toByteArray(), false)
        assertNotEquals(realDir, other.real.directory.name)
    }

    // ------------------------------------------------- the mistake that lost a vault

    @Test
    fun `a vault knows whether it is the decoy`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        assertFalse(configured.real.isDecoy)
        assertTrue(requireNotNull(configured.decoy).isDecoy)
    }

    @Test
    fun `unlocking reports the decoy as the decoy`() {
        store().configure("main".toByteArray(), "duress".toByteArray(), false)
        assertTrue(store().unlock("duress".toByteArray())!!.vault.isDecoy)
        assertFalse(store().unlock("main".toByteArray())!!.vault.isDecoy)
    }

    @Test
    fun `setting a duress password from inside the decoy is refused`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        val decoy = requireNotNull(configured.decoy)
        add(configured.real, "private.jpg", 2000, 1)

        // Allowing this rebuilds the slot file around the decoy's key and the real
        // vault's key is never written anywhere again. It happened once, to a real vault.
        var refused = false
        try {
            store().setDuress("duress".toByteArray(), decoy, "another".toByteArray(), false)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("must refuse to rebuild slots against a decoy", refused)

        // and the real vault still opens, with its contents
        val after = store().unlock("main".toByteArray())
        assertNotNull(after)
        assertFalse(after!!.vault.isDecoy)
        assertEquals(listOf("private.jpg"), after.vault.items().map { it.name })
    }

    @Test
    fun `the real password still opens the real vault after duress is changed`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        add(configured.real, "private.jpg", 2000, 2)
        add(requireNotNull(configured.decoy), "ordinary.jpg", 500, 3)

        assertTrue(store().changeDuress("duress".toByteArray(), "newduress".toByteArray(), false))

        val real = store().unlock("main".toByteArray())
        assertNotNull("the main password must survive a duress change", real)
        assertEquals(listOf("private.jpg"), real!!.vault.items().map { it.name })

        val decoy = store().unlock("newduress".toByteArray())
        assertNotNull(decoy)
        assertTrue(decoy!!.vault.isDecoy)
        assertEquals(listOf("ordinary.jpg"), decoy.vault.items().map { it.name })
        assertNull("the old duress password must stop working",
            store().unlock("duress".toByteArray()))
    }

    @Test
    fun `changing duress with the main password is refused`() {
        val configured = store().configure("main".toByteArray(), "duress".toByteArray(), false)
        add(configured.real, "private.jpg", 2000, 4)

        assertFalse(store().changeDuress("main".toByteArray(), "newduress".toByteArray(), false))

        // nothing was rewritten
        assertNotNull(store().unlock("main".toByteArray()))
        assertNotNull(store().unlock("duress".toByteArray()))
    }

    @Test
    fun `hkdf gives a different directory for a different key`() {
        val one = Crypto.hkdf(ByteArray(32) { 1 }, VaultStore.INFO_DIRECTORY, 10)
        val two = Crypto.hkdf(ByteArray(32) { 2 }, VaultStore.INFO_DIRECTORY, 10)
        assertFalse(one.contentEquals(two))
    }
}
