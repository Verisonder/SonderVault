package com.verisonder.sondervault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnlockLimitTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("sondervault-limit").toFile()
        base.deleteOnExit()
    }

    private fun limit() = UnlockLimit(File(base, UnlockLimit.FILE_NAME))

    private fun store() =
        VaultStore(base, argonMemKiB = 8, argonIterations = 1, argonParallelism = 1)

    @Test
    fun `the first few wrong attempts cost nothing`() {
        val limit = limit()
        repeat(UnlockLimit.FREE_ATTEMPTS) { limit.noteAttempt(now = 0L) }
        assertEquals(0L, limit.remainingMs(now = 0L))
    }

    @Test
    fun `the wait doubles with every attempt after that`() {
        val limit = limit()
        val free = UnlockLimit.FREE_ATTEMPTS
        assertEquals(UnlockLimit.FIRST_PENALTY_MS, limit.penaltyFor(free + 1))
        assertEquals(UnlockLimit.FIRST_PENALTY_MS * 2, limit.penaltyFor(free + 2))
        assertEquals(UnlockLimit.FIRST_PENALTY_MS * 4, limit.penaltyFor(free + 3))
        assertEquals(UnlockLimit.FIRST_PENALTY_MS * 8, limit.penaltyFor(free + 4))
    }

    @Test
    fun `the wait is capped rather than growing forever`() {
        val limit = limit()
        // Far past the point where a Long shift would wrap around to no shift at all,
        // which would quietly drop the wait back to fifteen seconds.
        for (failures in 40..200 step 7) {
            assertEquals(UnlockLimit.MAX_PENALTY_MS, limit.penaltyFor(failures))
        }
    }

    @Test
    fun `the wait runs down as time passes`() {
        val limit = limit()
        repeat(UnlockLimit.FREE_ATTEMPTS + 1) { limit.noteAttempt(now = 1_000L) }
        assertEquals(UnlockLimit.FIRST_PENALTY_MS, limit.remainingMs(now = 1_000L))
        assertEquals(UnlockLimit.FIRST_PENALTY_MS / 2, limit.remainingMs(now = 1_000L + UnlockLimit.FIRST_PENALTY_MS / 2))
        assertEquals(0L, limit.remainingMs(now = 1_000L + UnlockLimit.FIRST_PENALTY_MS))
    }

    @Test
    fun `winding the clock backwards does not lock the owner out for longer`() {
        val limit = limit()
        val start = 1_000_000L
        repeat(UnlockLimit.FREE_ATTEMPTS + 1) { limit.noteAttempt(now = start) }

        // The phone's clock goes back a year. Without a check the recorded deadline sits
        // in the far future and the vault refuses its own owner until the date catches
        // up again.
        val wayBack = start - 365L * 24 * 60 * 60 * 1000
        assertEquals(UnlockLimit.FIRST_PENALTY_MS, limit.remainingMs(now = wayBack))
    }

    @Test
    fun `a torn state file counts as nothing recorded`() {
        val file = File(base, UnlockLimit.FILE_NAME)
        file.writeText("not a number at all")
        assertEquals(0L, UnlockLimit(file).remainingMs())
    }

    @Test
    fun `opening the vault clears the count`() {
        val store = store()
        store.configure("main".toByteArray(), null, false)
        repeat(UnlockLimit.FREE_ATTEMPTS) { assertNull(store.unlock("wrong".toByteArray())) }

        assertNotNull(store.unlock("main".toByteArray()))
        assertEquals(0L, store.remainingLockoutMs())
        assertEquals(0, limit().failures())
    }

    @Test
    fun `the correct password is refused while the wait is running`() {
        val store = store()
        store.configure("main".toByteArray(), null, false)

        // One past the free attempts, so a wait is now owed.
        repeat(UnlockLimit.FREE_ATTEMPTS + 1) { assertNull(store.unlock("wrong".toByteArray())) }
        assertTrue(store.remainingLockoutMs() > 0L)

        // This is the point of the whole thing: the guard is in the store, so knowing the
        // password is not enough to spend the wait early.
        assertNull(store.unlock("main".toByteArray()))
    }

    @Test
    fun `a fingerprint unlock is not blocked by the wait and clears it`() {
        val store = store()
        store.configure("main".toByteArray(), null, false)
        // Taken the same way the biometric path gets it, through the public slot API,
        // so the test does not depend on anything internal to the vault.
        val slots = File(base, "slots.bin").readBytes()
        val masterKey = com.verisonder.sondervault.crypto.KeySlots
            .unlock(slots, "main".toByteArray())!!.masterKey

        repeat(UnlockLimit.FREE_ATTEMPTS + 1) { store.unlock("wrong".toByteArray()) }
        assertTrue(store.remainingLockoutMs() > 0L)

        assertTrue(store.openWithMasterKey(masterKey).vault.items().isEmpty())
        assertEquals(0L, store.remainingLockoutMs())
    }

    @Test
    fun `the duress password clears the count like any other unlock`() {
        val store = store()
        store.configure("main".toByteArray(), "duress".toByteArray(), false)
        repeat(UnlockLimit.FREE_ATTEMPTS) { store.unlock("wrong".toByteArray()) }

        // Someone made to open the vault must not then be told to wait, and must not be
        // left with a counter running against them afterwards.
        val opened = store.unlock("duress".toByteArray())
        assertNotNull(opened)
        assertTrue(opened!!.isDecoy)
        assertEquals(0L, store.remainingLockoutMs())
    }
}
