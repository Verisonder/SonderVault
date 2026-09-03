package com.verisonder.sondervault.vault

import java.io.File

/**
 * Makes guessing the password expensive in wall-clock time.
 *
 * A few attempts are free, because typing a password wrong is ordinary. After that every
 * further wrong one doubles the wait, up to a ceiling. Someone who picks the phone up and
 * starts trying gets four or five goes a minute at first and then a handful an hour.
 *
 * Kept here rather than in the unlock screen on the same reasoning that put the duress
 * guard in the vault layer: a screen that forgets a check must not be able to hand the
 * whole keyspace to whoever is holding the phone. Every route to a password unlock goes
 * through VaultStore.unlock, so the check goes there too.
 *
 * Takes a File rather than a Context so it runs in a plain JVM test, like everything else
 * in this package.
 *
 * **What this is not.** It slows a person down, not a computer. Anyone who can copy the
 * app's private storage off the device — which means a rooted or unlocked phone — can
 * attack slots.bin somewhere else entirely, where nothing here applies. Argon2id at
 * 64 MiB is what makes that expensive, and it remains the actual defence. This is the
 * defence against the phone being in someone's hands for ten minutes.
 */
class UnlockLimit(private val stateFile: File) {

    /**
     * Wrong attempts before any wait at all. Three is enough for a fumbled password and
     * far too few to be worth anything to someone guessing.
     */
    private val freeAttempts = FREE_ATTEMPTS

    class State(val failures: Int, val until: Long)

    /**
     * How long the next attempt has to wait, in milliseconds. Zero means go ahead.
     *
     * A deadline held as wall-clock time can be jumped by winding the phone's clock
     * forward, which is why the recorded failure count is the thing that matters and the
     * deadline is only derived from it. Winding the clock *backwards* would otherwise
     * park the deadline in the far future and lock the owner out of their own vault, so a
     * remaining time longer than the penalty itself is treated as a clock that moved and
     * rewritten to the penalty.
     */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        val state = read() ?: return 0L
        val left = state.until - now
        if (left <= 0L) return 0L
        val penalty = penaltyFor(state.failures)
        if (left > penalty) {
            write(State(state.failures, now + penalty))
            return penalty
        }
        return left
    }

    /**
     * Record an attempt before it is made, not after.
     *
     * Written first so that force-stopping the app while Argon2 is running does not
     * discard the attempt. The alternative counts only the attempts someone let finish,
     * which is every attempt they did not care about.
     */
    fun noteAttempt(now: Long = System.currentTimeMillis()) {
        val failures = (read()?.failures ?: 0) + 1
        write(State(failures, now + penaltyFor(failures)))
    }

    /** A vault opened. Nothing is owed. */
    fun clear() {
        stateFile.delete()
    }

    /** Whether anything is currently being counted, for a caller that wants to say so. */
    fun failures(): Int = read()?.failures ?: 0

    /**
     * Doubling, from FIRST_PENALTY_MS, capped.
     *
     * The cap is not politeness. Without one the wait passes a day within a dozen
     * attempts, and the person most likely to hit a dozen wrong attempts is the owner
     * with the wrong password in their head — locking them out for a week protects
     * nothing that fifteen minutes does not.
     */
    fun penaltyFor(failures: Int): Long {
        if (failures <= freeAttempts) return 0L
        // Long.shl uses only the low six bits of its argument, so a large shift silently
        // wraps around to no shift at all and the wait collapses back to fifteen seconds.
        // The exponent is clamped well below that before it is used.
        val steps = (failures - freeAttempts - 1).coerceIn(0, 20)
        return (FIRST_PENALTY_MS shl steps).coerceAtMost(MAX_PENALTY_MS)
    }

    // ------------------------------------------------------------------- on disk

    private fun read(): State? {
        if (!stateFile.exists()) return null
        // Anything unreadable counts as nothing recorded. A torn file should cost the
        // owner their history of typos, not their access to the vault.
        val parts = runCatching { stateFile.readText().trim().split(' ') }.getOrNull() ?: return null
        if (parts.size != 2) return null
        val failures = parts[0].toIntOrNull() ?: return null
        val until = parts[1].toLongOrNull() ?: return null
        if (failures < 0) return null
        return State(failures, until)
    }

    private fun write(state: State) {
        runCatching { stateFile.writeText("${state.failures} ${state.until}") }
    }

    companion object {
        const val FREE_ATTEMPTS = 3
        const val FIRST_PENALTY_MS = 15_000L
        const val MAX_PENALTY_MS = 15 * 60 * 1000L
        const val FILE_NAME = "attempts"
    }
}
