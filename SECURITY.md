# Security

SonderVault exists to keep what is in it private, so a security problem matters more here
than an ordinary bug.

## Reporting

Please report anything that could expose the contents of a vault, its keys or its password
**privately**, through [GitHub's private vulnerability reporting](https://github.com/Verisonder/SonderVault/security/advisories/new),
not in a public issue. Include what you found, how to reproduce it, and which version.

You will get an answer, and credit in the release notes if you would like it.

## Scope

In scope: the encryption and file formats (`docs/FORMAT.md`), the key slots and duress
password, unlocking and its rate limit, fingerprint unlock, sharing and backup files, and
anything that leaves decrypted data on the device.

Known and documented limits, not vulnerabilities:

- A rooted or unlocked phone lets someone copy the app's storage and attack the password
  elsewhere. Argon2id at 64 MiB is the defence there, not the in-app wait.
- While the vault is open its keys are in memory. They are wiped on lock, but the Android
  runtime may hold copies the app cannot reach.
- After a duress wipe the real password still opens an empty vault, by design, so the wipe
  is not obvious. Deleted files are best-effort shredded; flash storage may keep old copies.
- The saved code text file is not encrypted; the app says so when it is saved.

## Supported versions

Only the latest release gets fixes. Update over the installed app — never uninstall first,
which deletes the vault.
