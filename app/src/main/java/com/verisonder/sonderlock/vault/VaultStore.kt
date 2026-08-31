package com.verisonder.sonderlock.vault

import com.verisonder.sonderlock.crypto.Crypto
import com.verisonder.sonderlock.crypto.KeySlots
import java.io.File

/**
 * Owns the on-disk state: the slot file, and one directory per vault.
 *
 * Takes a base directory rather than a Context so the whole thing runs in a plain JVM
 * test. The Android layer passes `filesDir`.
 */
class VaultStore(
    private val baseDir: File,
    private val argonMemKiB: Int = Crypto.ARGON_MEM_KIB,
    private val argonIterations: Int = Crypto.ARGON_ITERS,
    private val argonParallelism: Int = Crypto.ARGON_PAR,
) {

    private val slotsFile = File(baseDir, "slots.bin")
    private val vaultsDir = File(baseDir, "vaults")

    val isConfigured: Boolean get() = slotsFile.exists()

    class Configured(val real: Vault, val decoy: Vault?)

    class Opened(val vault: Vault, val isDecoy: Boolean, val wiped: Boolean)

    /**
     * Write the slot file. Both passwords are set here, in one go, and that is a
     * constraint rather than a convenience.
     *
     * Slots cannot be told apart without the password that opens them — which is the
     * whole point — so nothing can later pick an unused one to write into. Changing
     * either password therefore means rebuilding this file from every password at once.
     *
     * @param duress null for no duress password at all.
     * @param duressWipes true destroys the real vault as the decoy opens; false opens the
     *   decoy and leaves everything alone, which is what makes a duress password usable
     *   more than once.
     */
    fun configure(mainPassword: ByteArray, duress: ByteArray?, duressWipes: Boolean): Configured {
        baseDir.mkdirs()
        vaultsDir.mkdirs()

        val realKey = Crypto.randomKey()
        val decoyKey = if (duress != null) Crypto.randomKey() else null

        val entries = ArrayList<KeySlots.Entry>()
        entries.add(KeySlots.Entry(mainPassword, KeySlots.VAULT_REAL, false, realKey))
        if (duress != null && decoyKey != null) {
            entries.add(KeySlots.Entry(duress, KeySlots.VAULT_DECOY, duressWipes, decoyKey))
        }

        // Unlock does not take these: KeySlots records them in the slot header and reads
        // them back, so a file written with one cost still opens after the default moves.
        writeSlots(KeySlots.build(entries, argonMemKiB, argonIterations, argonParallelism))

        return Configured(
            real = openVault(realKey),
            decoy = decoyKey?.let { openVault(it) },
        )
    }

    /**
     * Open from a master key that has already been recovered — the biometric path, where
     * there is no password to derive anything from.
     *
     * Only ever the real vault. A fingerprint that opened the decoy would be a fingerprint
     * that could be pressed against the phone by someone holding your hand, and there is
     * no gesture for "open the other one".
     */
    fun openWithMasterKey(masterKey: ByteArray): Opened =
        Opened(openVault(masterKey), isDecoy = false, wiped = false)

    /**
     * Add, change or remove the duress password on a vault that already exists.
     *
     * The slot file is rebuilt from scratch, because slots cannot be told apart without
     * the password that opens them and so none can be singled out and rewritten. That is
     * why this needs the main password as well: it is the only way to prove which vault
     * is being kept, and the existing master key is carried across so nothing already
     * stored is orphaned.
     *
     * @param duress null removes the duress password entirely.
     * @return the decoy vault, ready to have its ordinary-looking photos put in it.
     */
    fun setDuress(
        mainPassword: ByteArray,
        realVault: Vault,
        duress: ByteArray?,
        duressWipes: Boolean,
    ): Vault? {
        require(confirms(mainPassword, realVault)) { "that is not the main password" }

        val realKey = realVault.masterKeyCopy()
        val decoyKey = if (duress != null) Crypto.randomKey() else null

        val entries = ArrayList<KeySlots.Entry>()
        entries.add(KeySlots.Entry(mainPassword, KeySlots.VAULT_REAL, false, realKey))
        if (duress != null && decoyKey != null) {
            entries.add(KeySlots.Entry(duress, KeySlots.VAULT_DECOY, duressWipes, decoyKey))
        }
        writeSlots(KeySlots.build(entries, argonMemKiB, argonIterations, argonParallelism))
        Crypto.wipe(realKey)

        return decoyKey?.let { openVault(it) }
    }

    /**
     * Change an existing duress password, or its behaviour, using only the current duress
     * password.
     *
     * The old password identifies its own slot and that slot alone is rewritten, so the
     * main password never has to be typed to change this. The decoy's master key is
     * carried across unchanged — a fresh one would orphan every photo already in it.
     */
    fun changeDuress(currentDuress: ByteArray, newDuress: ByteArray, wipes: Boolean): Boolean {
        if (!isConfigured) return false
        val blob = slotsFile.readBytes()
        val unlocked = KeySlots.unlock(blob, currentDuress) ?: return false
        if (!unlocked.isDecoy) {
            // That was the main password, not the duress one. Rewriting the real slot
            // here would be a very expensive mistake.
            Crypto.wipe(unlocked.masterKey)
            return false
        }
        val updated = KeySlots.replaceSlot(
            blob, unlocked.slotIndex, newDuress, KeySlots.VAULT_DECOY, wipes, unlocked.masterKey,
        )
        Crypto.wipe(unlocked.masterKey)
        if (updated == null) return false
        writeSlots(updated)
        return true
    }

    /** Remove the duress password and the vault it opened. */
    fun removeDuress(currentDuress: ByteArray): Boolean {
        if (!isConfigured) return false
        val blob = slotsFile.readBytes()
        val unlocked = KeySlots.unlock(blob, currentDuress) ?: return false
        if (!unlocked.isDecoy) {
            Crypto.wipe(unlocked.masterKey)
            return false
        }
        val decoy = openVault(unlocked.masterKey)
        Crypto.wipe(unlocked.masterKey)
        writeSlots(KeySlots.clearSlot(blob, unlocked.slotIndex))
        // The decoy's own photos go with it. Leaving a directory nothing can open is a
        // stack of bytes that only ever looks suspicious.
        decoy.destroyContents()
        decoy.directory.deleteRecursively()
        return true
    }

    /**
     * Whether a decoy exists at all, told by looking for a second directory rather than
     * by anything recorded. Nothing on disk states it, and the slot file deliberately
     * cannot answer the question.
     */
    fun hasSecondVault(exclude: Vault): Boolean =
        (vaultsDir.listFiles()?.count { it.isDirectory && it.name != exclude.directory.name } ?: 0) > 0

    fun unlock(password: ByteArray): Opened? {
        if (!isConfigured) return null
        val unlocked = KeySlots.unlock(slotsFile.readBytes(), password) ?: return null
        val vault = openVault(unlocked.masterKey)
        var wiped = false
        if (unlocked.wipe) {
            destroyEverythingExcept(vault.directory)
            wiped = true
        }
        return Opened(vault, unlocked.isDecoy, wiped)
    }

    /**
     * Confirm that a typed password belongs to the vault that is already open.
     *
     * Deliberately does not honour the wipe flag. This is a confirmation prompt, not an
     * unlock: someone mistyping their duress password into it should not destroy the
     * vault they are standing in. The duress password simply fails to confirm, because it
     * unwraps a different key.
     */
    fun confirms(password: ByteArray, vault: Vault): Boolean {
        if (!isConfigured) return false
        val unlocked = KeySlots.unlock(slotsFile.readBytes(), password) ?: return false
        val matches = vault.matchesMasterKey(unlocked.masterKey)
        Crypto.wipe(unlocked.masterKey)
        return matches
    }

    /**
     * Where a vault lives is derived from its own master key, so the directory names are
     * unattributable: nothing on disk says which of them is the real one, and a directory
     * whose key you do not hold cannot even be identified as yours.
     */
    private fun openVault(masterKey: ByteArray): Vault {
        val name = Crypto.hkdf(masterKey, INFO_DIRECTORY, 10)
            .joinToString("") { "%02x".format(it) }
        val directory = File(vaultsDir, name).apply { mkdirs() }
        return Vault(directory, masterKey)
    }

    /**
     * The duress wipe.
     *
     * It cannot look up the real vault by name, because that name comes from the real
     * master key and a duress unlock never sees it. So it deletes everything that is not
     * the vault it just opened. Anything it cannot identify as the decoy is, by
     * definition, the thing being hidden.
     *
     * The slot file is left alone deliberately. Removing the real slot would make the
     * real password fail outright, which is itself a signal; leaving it means that
     * password still opens something, and what it opens is empty.
     */
    private fun destroyEverythingExcept(keep: File) {
        val others = vaultsDir.listFiles()?.filter { it.isDirectory && it.name != keep.name }
            ?: return
        for (directory in others) {
            val files = directory.walkTopDown().filter { it.isFile }
                .sortedByDescending { it.length() }
            for (file in files) file.delete()
            directory.deleteRecursively()
        }
    }

    private fun writeSlots(blob: ByteArray) {
        val temp = File(baseDir, "slots.new")
        temp.writeBytes(blob)
        if (!temp.renameTo(slotsFile)) {
            slotsFile.writeBytes(blob)
            temp.delete()
        }
    }

    /** Everything the app holds, for the storage figure shown at setup and on uninstall. */
    fun totalSizeOnDisk(): Long =
        baseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val INFO_DIRECTORY = "sonderlock:dir:v1"
    }
}
