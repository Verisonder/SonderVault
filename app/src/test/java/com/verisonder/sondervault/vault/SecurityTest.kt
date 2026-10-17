package com.verisonder.sondervault.vault

import com.verisonder.sondervault.bundle.Bundle
import com.verisonder.sondervault.bundle.BundleEntry
import com.verisonder.sondervault.bundle.BundleReader
import com.verisonder.sondervault.crypto.Crypto
import com.verisonder.sondervault.crypto.RecoveryPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The checks added in the security pass. Small Argon2 parameters, as in VaultTest. */
class SecurityTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("sondervault-sec").toFile()
        base.deleteOnExit()
    }

    private fun store() = VaultStore(base, argonMemKiB = 8, argonIterations = 1, argonParallelism = 1)

    @Test
    fun `names from outside cannot climb out of a folder`() {
        assertFalse(SafeNames.fileName("../../Android/data/x.jpg").contains('/'))
        assertFalse(SafeNames.fileName("..\\..\\x").contains('\\'))
        assertFalse(SafeNames.fileName("../x").startsWith("."))
        assertFalse(SafeNames.fileName("../").contains('/'))
        assertEquals("file", SafeNames.fileName(""))
        assertEquals("photo.jpg", SafeNames.fileName("photo.jpg"))
        assertFalse(SafeNames.fileName("a‮gpj.exe").contains('‮'))
        assertTrue(SafeNames.fileName("x".repeat(500) + ".jpg").length <= 180)
        assertTrue(SafeNames.fileName("x".repeat(500) + ".jpg").endsWith(".jpg"))
    }

    @Test
    fun `types from outside are reduced to a plain type or octet-stream`() {
        assertEquals("image/jpeg", SafeNames.mimeType("IMAGE/JPEG"))
        assertEquals("text/plain", SafeNames.mimeType("text/plain; charset=utf-8"))
        assertEquals("application/octet-stream", SafeNames.mimeType("../../etc"))
        assertEquals("application/octet-stream", SafeNames.mimeType(""))
    }

    @Test
    fun `imported names are cleaned`() {
        val vault = store().configure("main-password".toByteArray(), null, false).real
        val item = vault.importItem("../../evil.jpg", "image/JPEG", ByteArrayInputStream(ByteArray(10)))
        assertFalse(item.name.contains('/'))
        assertEquals("image/jpeg", item.mimeType)
        assertFalse(vault.items().single().name.contains('/'))
    }

    @Test
    fun `the confirmation prompt shares the unlock wait`() {
        val store = store()
        val vault = store.configure("main-password".toByteArray(), null, false).real
        repeat(UnlockLimit.FREE_ATTEMPTS + 1) {
            assertFalse(store.confirms("wrong".toByteArray(), vault))
        }
        assertTrue(store.remainingLockoutMs() > 0L)
        // The right password is refused while the wait runs, exactly like the unlock.
        assertFalse(store.confirms("main-password".toByteArray(), vault))
    }

    @Test
    fun `a correct confirmation clears the count`() {
        val store = store()
        val vault = store.configure("main-password".toByteArray(), null, false).real
        repeat(UnlockLimit.FREE_ATTEMPTS) { assertFalse(store.confirms("wrong".toByteArray(), vault)) }
        assertTrue(store.confirms("main-password".toByteArray(), vault))
        assertEquals(0L, store.remainingLockoutMs())
    }

    @Test
    fun `changing the duress password is counted too`() {
        val store = store()
        store.configure("main-password".toByteArray(), "duress-pass".toByteArray(), false)
        repeat(UnlockLimit.FREE_ATTEMPTS + 1) {
            assertFalse(store.changeDuress("wrong".toByteArray(), "new-duress".toByteArray(), false))
        }
        assertFalse(store.changeDuress("duress-pass".toByteArray(), "new-duress".toByteArray(), false))
    }

    @Test
    fun `a duress wipe removes the fingerprint copy of the key`() {
        val store = store()
        store.configure("main-password".toByteArray(), "duress-pass".toByteArray(), true)
        val bio = File(base, "bio.bin").apply { writeBytes(ByteArray(60) { 7 }) }
        val opened = store.unlock("duress-pass".toByteArray())
        assertNotNull(opened)
        assertTrue(opened!!.wiped)
        assertFalse(bio.exists())
    }

    @Test
    fun `a duress wipe leaves no index behind`() {
        val store = store()
        val configured = store.configure("main-password".toByteArray(), "duress-pass".toByteArray(), true)
        configured.real.importItem("a.jpg", "image/jpeg", ByteArrayInputStream(ByteArray(100)))
        val realDir = configured.real.directory
        assertTrue(File(realDir, "index").exists())
        store.unlock("duress-pass".toByteArray())
        assertFalse(File(realDir, "index").exists())
    }

    @Test
    fun `a bundle whose entries point outside the file is refused`() {
        val phrase = listOf("abandon", "ability", "able", "about", "above", "absent")
        val salt = Crypto.random(Bundle.SALT_BYTES)
        val nonce = Crypto.random(Crypto.GCM_NONCE_BYTES)
        val key = RecoveryPhrase.toKey(phrase, salt, 8, 1, 1)
        val entry = BundleEntry("x.jpg", "image/jpeg", 10, 0, ByteArray(32), offset = 5, length = 1L shl 40)
        val manifest = Bundle.serialise(listOf(entry))
        val header = Bundle.header(salt, nonce, manifest.size + Crypto.GCM_TAG_BYTES, 8, 1, 1)
        val sealed = Crypto.gcmSeal(key, nonce, manifest, header)
        val file = File(base, "crafted.sondervault").apply { writeBytes(header + sealed + ByteArray(200)) }
        try {
            BundleReader.open(file, phrase)
            fail("a crafted manifest was accepted")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `a bundle asking for a gigabyte of memory is refused before deriving anything`() {
        val phrase = listOf("abandon", "ability", "able", "about", "above", "absent")
        val header = Bundle.header(ByteArray(16), ByteArray(12), 100, 1_048_576, 1, 1)
        val file = File(base, "greedy.sondervault").apply { writeBytes(header + ByteArray(200)) }
        try {
            BundleReader.open(file, phrase)
            fail("accepted")
        } catch (e: IOException) {
            assertNull(null)
        }
    }

    @Test
    fun `the index is written in exactly the old format`() {
        val item = VaultItem("ab12", "a\tb.jpg", "image/jpeg", 10, 20, 30, ByteArray(32) { it.toByte() }, true)
        val expected = "SVIX1\nab12\ta\\tb.jpg\timage/jpeg\t10\t20\t30\t" +
            (0 until 32).joinToString("") { "%02x".format(it) } + "\t1\n"
        assertEquals(expected, String(VaultIndex.serialise(listOf(item)), Charsets.UTF_8))
        assertEquals(listOf(item), VaultIndex.parse(expected.toByteArray()))
    }

    @Test
    fun `locking zeroes every item key`() {
        val vault = store().configure("main-password".toByteArray(), null, false).real
        vault.importItem("a.jpg", "image/jpeg", ByteArrayInputStream(ByteArray(10)))
        val held = vault.items().single()
        assertTrue(held.fileKey.any { it != 0.toByte() })
        vault.close()
        assertTrue(held.fileKey.all { it == 0.toByte() })
    }
}
