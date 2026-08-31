# SonderLock

A private space on your phone for photos, videos and files. Everything is encrypted on
the device and stays there.

- **App ID:** `com.verisonder.sonderlock`
- **Licence:** GPL-3.0-only
- **Status:** early. The crypto layer exists and is tested; there is no app around it yet.

---

## What it does

- **Password or fingerprint** to open the vault.
- **A second password that opens a decoy vault** holding photos you chose at setup, so
  handing over a password under pressure reveals something ordinary rather than an empty
  screen or an error. Optionally it also destroys the real vault as it opens.
- **Share as one encrypted file**, unlocked by a six-word code generated fresh for each
  share. A code that leaks costs that bundle and nothing else.
- **Export everything to one file** as a backup, with its own code. Uninstalling the app
  destroys the vault, by design — this is the way back in.
- **Screenshot and screen recording blocked** throughout, including the recents thumbnail.

## What it does not claim

`FLAG_SECURE` stops screenshots, screen recording and the recents preview. It does not
stop a second phone pointed at the screen, and it does not stop a rooted device.

Bundles are described as opening in SonderLock. That is the file format, not enforcement.
The source is public and the format is documented, so anyone with the code and a copy of
this repository can decrypt a bundle. The encryption is what protects it; needing the app
is convenience.

A recovery code cannot be reset. There is no copy of it anywhere, and no way to recover a
bundle without it. That is the point, and it is the one thing worth writing down.

## How it is built

| | |
|---|---|
| Key derivation | Argon2id, 64 MiB, t=3, p=2 |
| Content | AES-256-CTR with HMAC-SHA256 per 1 MiB block |
| Key wrapping | AES-256-GCM |
| Codes | six words from the BIP-39 English list, 66 bits |

CTR rather than GCM for content because **GCM cannot seek**, and scrubbing a video means
jumping to an arbitrary byte. Integrity comes from a MAC per block, so playback does not
have to read a 2 GB file before showing the first frame.

Every layout is in [`docs/FORMAT.md`](docs/FORMAT.md).

## Verification

The formats are implemented twice. `tools/reference.py` is a Python implementation used
to prove the byte layouts and the seek arithmetic, and to emit fixed vectors;
`app/src/test/.../CryptoVectorTest.kt` is generated from those vectors and checks the
Kotlin against them. The two agreeing byte for byte is the test. Where they disagree, the
failure names the value that was expected.

```bash
cd tools
python3 test_reference.py                  # 31 checks on the reference itself
python3 gen_vectors.py > vectors.json      # regenerate the vectors
python3 emit_test.py                       # regenerate the Kotlin test from them
```

## Repository layout

```
app/src/main/java/com/verisonder/sonderlock/crypto/
    Crypto.kt           Argon2id, HKDF, GCM, CTR, HMAC, wiping
    KeySlots.kt         the wrapped master keys, fixed size, indistinguishable
    VaultFile.kt        the .slf container: streaming writer, seekable reader
    RecoveryPhrase.kt   six-word codes
app/src/main/res/raw/bip39_en.txt
app/src/test/java/...   generated vector tests
docs/FORMAT.md          byte layouts and the reasoning behind them
tools/                  the Python reference and the generators
```
