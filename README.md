<div align="center">

<img src="docs/logo.png" width="140" alt="SonderVault">

# SonderVault

**A private space on your phone for photos, videos and files.**
Everything is encrypted on the device and stays there.

`com.verisonder.sondervault` · GPL-3.0-only · Android 9 and above

</div>

---

## What it does

**Hides things properly.** Anything you add is encrypted and the original is removed from
your phone, so it is gone from your gallery rather than tucked into a folder.

**Opens with a password or a fingerprint.** The password is the only real key; the
fingerprint is a second wrapping of it, and turning it off deletes that wrapping.

**Has a duress password.** A second password that opens a different set of photos — ones
you chose, so it looks like an ordinary vault rather than an empty one. Optionally it
destroys the real vault as it opens. Nothing anywhere in the app hints that a second
vault exists.

**Shares as one encrypted file.** Any selection becomes a single `.sondervault` file with
its own six-word code, shown with a QR. A code that leaks costs that file and nothing
else. The recipient can scan the code or type it.

**Backs up the same way.** A backup is a share of everything, to yourself.

**Reads what it holds.** Photos zoom, videos play and scrub, PDFs and text files open —
all decrypted as they are read, never copied out to a temporary file.

**Blocks screenshots**, screen recording and the recents thumbnail, everywhere in the app.

## What it does not claim

`FLAG_SECURE` stops screenshots, recording and the recents preview. It does not stop a
second phone pointed at the screen, and it does not stop a rooted device.

A bundle is described as opening in SonderVault. That is the file format, not enforcement.
The source is public and the format is documented, so anyone with the code and this
repository can decrypt one. The encryption protects it; needing the app is convenience.

**A code cannot be reset.** There is no copy of it anywhere and no recovery route in the
format. That is the point, and it is the one thing worth writing down.

If cloud photo backup is on, a photo you hide is still in your cloud account. SonderVault
removes it from the phone and has no way to reach the copy there, or to know it exists.

## How it is built

| | |
|---|---|
| Key derivation | Argon2id, 64 MiB, t=3, p=2 |
| Content | AES-256-CTR with HMAC-SHA256 on every 1 MiB block |
| Key wrapping | AES-256-GCM |
| Derivation | HKDF-SHA256 |
| Codes | six words from the BIP-39 English list, 66 bits |

One master key per vault, wrapped separately by each thing that can open it. Every item
gets its own key, which is what makes sharing one photo possible without handing over the
vault — and what makes the duress wipe instant, since destroying a wrapped master key
takes milliseconds where erasing 20 GB does not.

**CTR rather than GCM for content, because GCM cannot seek.** Scrubbing a video means
jumping to an arbitrary byte. Integrity comes from a MAC on each block, so playback does
not have to read a 2 GB file before showing the first frame. The same property lets a PDF
be rendered, and a video frame grabbed, straight out of the encrypted file.

Every byte layout is in [`docs/FORMAT.md`](docs/FORMAT.md).

## Verification

The formats are implemented twice. `tools/reference.py` is a Python implementation used
to prove the layouts and the seek arithmetic and to emit fixed vectors;
`CryptoVectorTest.kt` is generated from those vectors and checks the Kotlin against them.
Two independent implementations agreeing byte for byte is the test.

```bash
cd tools
python3 test_reference.py                  # 31 checks on the reference itself
python3 gen_vectors.py > vectors.json      # regenerate the vectors
python3 emit_test.py                       # regenerate the Kotlin test from them
```

Alongside those, the vault and bundle layers are covered by JVM tests — round trips,
tampering, truncation, parameter downgrade, the duress wipe, and the case where the wrong
vault is handed to the code that rebuilds the key slots.

## Permissions

| | |
|---|---|
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | to show your gallery and remove the originals |
| `MANAGE_MEDIA` | to remove them without a confirmation each time |
| `CAMERA` | to scan a shared file's code, asked only when you open the scanner |

**There is no `INTERNET` permission.** Nothing in this app can send anything anywhere,
whatever the code says, so the promise that your photos stay on the device does not rest
on anyone reading the source and believing it.

## Repository layout

```
app/src/main/java/com/verisonder/sondervault/
    crypto/     Argon2id, HKDF, the key slots, the .slf container
    vault/      an open vault, its index, the session
    bundle/     the shared and backed-up .sondervault format
    media/      importing, exporting, thumbnails, playback, previews
    ui/         every screen
docs/FORMAT.md  byte layouts and the reasoning behind them
tools/          the Python reference and the generators
```

## Building

JDK 17 and Gradle 8.9. There is no wrapper in the repository, so CI installs Gradle and no
binary jar sits in source control.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```
