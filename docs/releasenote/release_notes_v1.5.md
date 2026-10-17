## SonderVault 1.5

**A new look.** SonderVault now shares its design with SonderShield: a black page, one
accent colour, bold Unbounded headings and DM Sans text, rounded cards and pill buttons.
The vault opens on a header with your item count and All / Photos / Videos / Files chips,
with your photos on a panel under the blue swoosh. Picking items turns the top bar blue
and frames each picked photo. Settings is split into numbered sections, and a new **Look**
section lets you choose Default, Light, System or Material You, and an accent colour.

The unlock screen still shows no name, logo or heading.

### Security

- **Every password check now waits like the unlock screen.** The prompt that asks for your
  password before files leave the vault, and the duress password screen, could be used to
  guess the main password with no wait at all.
- **Names from outside the app are cleaned.** A shared file made by someone else could
  carry a file name that, when put back on Android 9, wrote outside the gallery folder.
- **Damaged or hostile shared files are refused before they are read.** A file asking for
  a gigabyte of memory, or listing entries outside itself, no longer crashes the app.
- **A duress wipe overwrites the index** that holds every item key before deleting it, and
  removes the fingerprint's copy of the real key.
- **Saving the index can no longer destroy it.** A failed save leaves the previous index
  exactly as it was.
- **Copying the six-word code** marks it as sensitive, so keyboards do not keep it, and
  clears it from the clipboard after a minute. The keyboard no longer learns the code as
  you type it.
- **Leaving the app from the file picker** now locks the vault within two minutes instead
  of leaving it open.
- **Taps through another app's window drawn on top are ignored**, so an overlay cannot
  trick you into pressing a button or typing your password.
- **Fewer keys left in memory.** Locking zeroes every item key, not just the master key;
  the index is read without turning keys into text; decrypted blocks and password bytes
  are wiped as soon as they have been used.

Nothing about how your vault is stored has changed. Update over the installed app — do not
uninstall first, which deletes the vault.

Requires Android 9 or later.
