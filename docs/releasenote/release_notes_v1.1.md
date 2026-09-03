## SonderVault 1.1

**A wrong password now costs time.** Three attempts are free, because typing a password
wrong is ordinary. After that every further wrong one doubles the wait — fifteen seconds,
then thirty, then a minute — up to a ceiling of fifteen minutes. The unlock screen shows
how long is left and nothing else: not how many attempts have been made, not how many are
left, not whether any of them were close.

This slows down a person holding the phone. It is not a defence against someone who has
copied the app's storage off a rooted device and is attacking it elsewhere, where nothing
in the app is running — Argon2id at 64 MiB is what makes that expensive, and it has not
changed. The wait is enforced where the vault is opened rather than on the screen that
asks, so knowing the password does not let you spend it early.

Your fingerprint is not affected. It is neither blocked by the wait nor counted against
it, and using it clears the count. So does a duress password.

### Fixed

- **An import that was still finishing when the app went to the background could damage
  the vault's index.** Locking wipes the index key immediately, and anything still writing
  at that moment wrote a file that the real key could no longer read. The index holds
  every file key in the vault, so this was the whole vault rather than one photo.
- **Items could go missing from the index.** A screen full of tiles rebuilding their
  thumbnails writes the index once per tile, and two of those — or one of them and an
  import — could overwrite each other.
- **Turning fingerprint unlock on and then cancelling the prompt left it broken.** The
  offer stayed on the unlock screen and failed every time, until the switch was turned off
  and on again.
- **Locking the app did not take the master key out of memory**, only out of reach.
- The key slot file is now written and forced to disk before it replaces the old one, so
  losing power partway through cannot leave it empty.
- Files above 2 GB could be cut short when read.
- A crash report could record the name of a file that had failed to import.
- Backups and vaults written by a future version stay readable by this one.
