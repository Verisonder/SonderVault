The first release.

A private space on your phone for photos, videos and files. Everything is encrypted on
the device and stays there.

- **Import photos, videos and files.** The original is removed from your phone, not just
  hidden in a folder.
- **Open with a password or a fingerprint.** The password is the only real key.
- **A duress password** that opens a different set of photos you chose, and can destroy
  the real vault as it opens. Nothing in the app hints that a second vault exists.
- **Share as one encrypted file** with its own six-word code, shown as a QR. Scan it or
  type it. Backing up is the same thing, to yourself.
- **View what you store.** Photos zoom, videos play and scrub, PDFs and text files open —
  decrypted as they are read, never copied out to a temporary file.
- **Screenshots, screen recording and the recents thumbnail are blocked** throughout.

**Argon2id** at 64 MiB for the password, **AES-256-CTR with HMAC-SHA256** on every block
of content, **AES-256-GCM** for key wrapping. No `INTERNET` permission, so nothing in the
app can send anything anywhere whatever the code says.

Two things worth knowing before you rely on it. **A six-word code cannot be reset** —
there is no copy of it anywhere and no recovery route in the format. And if cloud photo
backup is on, a photo you hide is still in your cloud account; SonderVault removes it
from the phone and has no way to reach the copy there.

Requires Android 9 or later.
