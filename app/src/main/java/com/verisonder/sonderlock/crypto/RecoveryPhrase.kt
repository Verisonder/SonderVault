package com.verisonder.sonderlock.crypto

import java.security.SecureRandom

/**
 * Six-word codes. See docs/FORMAT.md section 5.
 *
 * The phrase IS the secret. It is not a mnemonic standing in for a key held somewhere
 * else — there is no other copy, and losing it means the bundle is gone.
 *
 * Six BIP-39 words is 66 bits. Against Argon2id at 64 MiB per guess, searching that
 * space is not something anyone does. Words beat a random character string here because
 * this gets written on paper and typed back a year later, and BIP-39 words are unique in
 * their first four letters, so a typo is correctable without guessing at spelling.
 */
class RecoveryPhrase private constructor(private val words: List<String>) {

    private val exact: Set<String> = words.toHashSet()
    private val byPrefix: Map<String, String> = words.associateBy { it.take(PREFIX_LENGTH) }
    private val rng = SecureRandom()

    /** A fresh code. Generated per bundle, never reused: a leak costs that bundle alone. */
    fun generate(count: Int = WORD_COUNT): List<String> =
        List(count) { words[rng.nextInt(words.size)] }

    /**
     * Parse what the user typed, correcting on the four-letter prefix. Null if any word
     * cannot be resolved, so the caller can point at the problem rather than running a
     * 64 MiB derivation that was never going to work.
     */
    fun parse(typed: String): List<String>? {
        val parts = typed.trim().lowercase().split(SEPARATOR).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            val cleaned = part.filter { it in 'a'..'z' }
            val match = when {
                cleaned in exact -> cleaned
                cleaned.length >= PREFIX_LENGTH -> byPrefix[cleaned.take(PREFIX_LENGTH)]
                else -> null
            } ?: return null
            out.add(match)
        }
        return out
    }

    /** Words that begin with what has been typed so far, for the entry field. */
    fun suggest(fragment: String, limit: Int = 4): List<String> {
        val f = fragment.trim().lowercase()
        if (f.isEmpty()) return emptyList()
        return words.asSequence().filter { it.startsWith(f) }.take(limit).toList()
    }

    fun contains(word: String): Boolean = word.lowercase() in exact

    companion object {
        const val WORD_COUNT = 6
        const val PREFIX_LENGTH = 4
        const val WORDLIST_SIZE = 2048
        const val WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

        private val SEPARATOR = Regex("[^a-z]+")

        fun from(lines: Sequence<String>): RecoveryPhrase {
            val words = lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            require(words.size == WORDLIST_SIZE) { "wordlist must hold $WORDLIST_SIZE words, found ${words.size}" }
            return RecoveryPhrase(words)
        }

        /** Canonical form: lower case, single spaces. What the key is derived from. */
        fun canonical(words: List<String>): String = words.joinToString(" ") { it.lowercase() }

        fun toKey(
            words: List<String>,
            salt: ByteArray,
            memKiB: Int = Crypto.ARGON_MEM_KIB,
            iterations: Int = Crypto.ARGON_ITERS,
            parallelism: Int = Crypto.ARGON_PAR,
        ): ByteArray {
            val phrase = canonical(words).toByteArray(Charsets.UTF_8)
            try {
                return Crypto.argon2id(phrase, salt, memKiB, iterations, parallelism)
            } finally {
                Crypto.wipe(phrase)
            }
        }

        /**
         * The text file the user saves alongside a bundle.
         *
         * Named after the bundle deliberately: six words with no indication of what they
         * open is a puzzle in two years. Says plainly that the file is not protected,
         * because once it leaves the app it is a plain text file and nothing here is
         * guarding it.
         */
        fun saveableText(words: List<String>, bundleName: String, isBackup: Boolean): String {
            val kind = if (isBackup) "backup" else "shared file"
            return buildString {
                appendLine("SonderLock — code for $bundleName")
                appendLine()
                appendLine(canonical(words))
                appendLine()
                appendLine("Opens: $bundleName")
                appendLine("Type: $kind")
                if (isBackup) {
                    appendLine()
                    appendLine("This is a recovery code. It is the only way back into this backup.")
                    appendLine("There is no reset and no copy held anywhere else.")
                }
                appendLine()
                appendLine("This text file is not encrypted. Keep it somewhere you would keep a key.")
            }
        }
    }
}
