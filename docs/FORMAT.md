# SonderLock — on-disk formats

Version 1. Every layout here is exercised by `tools/test_reference.py` and pinned by
`app/src/test/resources/vectors.json`.

Nothing in this document is secret. The security rests entirely on the keys, and the
source is public, so a format that needed hiding would be the wrong format.

---

## 1. Key hierarchy

One 256-bit **master key** per vault, generated at setup from `SecureRandom` and never
derived from a password. It is wrapped separately by each thing that may open it.

```
                        master key (32 bytes, random)
                                  |
        +-------------------------+-------------------------+
        |                         |                         |
  password slot            biometric slot             duress slot
  Argon2id -> KEK          Android Keystore           Argon2id -> KEK
  AES-256-GCM wrap         AES-256-GCM wrap           (unwraps the DECOY vault)
```

Every stored item gets its own random **file key**, wrapped by the master key. That is
what makes sharing possible: a bundle hands over one file's key without handing over the
vault.

It is also what makes the duress wipe instant. Destroying the wrapped master key makes
every byte in the vault unrecoverable in milliseconds. Erasing 20 GB of video takes long
enough that someone watching the screen would notice.

---

## 2. `slots.bin` — 302 bytes, always

```
offset  size  field
     0     4  magic "SLK1"
     4     1  version = 1
     5     4  Argon2 memory, KiB, big endian     (65536)
     9     4  Argon2 iterations, big endian      (3)
    13     1  Argon2 parallelism                 (2)
    14    16  salt
    30    68  slot 0
    98    68  slot 1
   166    68  slot 2
   234    68  slot 3
```

Each slot:

```
offset  size  field
     0    12  AES-GCM nonce
    12    56  AES-256-GCM sealed payload (40 bytes plaintext + 16 byte tag)
```

Sealed payload, always exactly 40 bytes:

```
offset  size  field
     0     1  vaultId   0 = real, 1 = decoy
     1     1  flags     bit 0 = wipe the real vault on unlock
     2     6  random padding
     8    32  master key
```

The 30-byte header is passed as GCM **associated data**, so editing the Argon2
parameters to something cheap is detected rather than silently accepted.

**One salt for the whole file, not one per slot.** Trying a password is then a single
Argon2 run followed by four cheap GCM attempts. Per-slot salts would mean four Argon2
runs per attempt, four times the unlock delay, for no gain — the salt exists to stop
precomputation across devices, and one per file does that.

**Unused slots hold random bytes.** A vault with no duress password is byte-for-byte
the same size and shape as one with. Slot order is shuffled at write time so the real
slot is not reliably first. Nothing outside the sealed payload says what a slot opens,
or whether it opens anything at all.

Unlocking: derive the KEK once, try to open each slot in turn, and read `vaultId` and
`flags` out of whichever payload authenticates.

---

## 3. `.slf` — one per stored item

AES-256-CTR in blocks, each block authenticated with HMAC-SHA256. Encrypt-then-MAC.

```
offset  size  field
     0     4  magic "SLF1"
     4     1  version = 1
     5     1  block size, log2      (20 = 1 MiB)
     6     8  nonce
    14     8  plaintext size, big endian
    22    32  HMAC-SHA256(macKey, bytes 0..21)
    54     -  block 0 ciphertext, then 32 byte MAC
     -     -  block 1 ciphertext, then 32 byte MAC
     ...
```

Container size is `54 + plainSize + 32 * ceil(plainSize / blockSize)`. An empty file
writes no blocks at all and is 54 bytes.

Keys are split from the file key:

```
encKey = HKDF-SHA256-Expand(fileKey, "sonderlock:enc:v1", 32)
macKey = HKDF-SHA256-Expand(fileKey, "sonderlock:mac:v1", 32)
```

Extract is skipped because the input is already a uniform 256-bit key.

Per-block MAC:

```
HMAC-SHA256(macKey, nonce || uint64be(blockIndex) || uint64be(len(ct)) || ct)
```

The CTR counter block is `nonce (8) || uint64be(counter)`, and the counter is **global
across the file**: block *i* starts at `i * blockSize / 16`. A block boundary is
therefore also a counter boundary, which is the whole point — seeking to any block
needs no knowledge of the blocks before it.

### Why not GCM

**GCM cannot seek.** Scrubbing a video means jumping to an arbitrary byte, and CTR lets
you jump by setting the counter. Media3 already ships `AesCipherDataSource` built on
exactly this, so playback becomes a custom `DataSource` rather than a rewrite of the
player.

The per-block MACs are what keep integrity. A single MAC over a 2 GB file would mean
reading all of it before showing the first frame.

### What each field defends

- `blockIndex` in the MAC — blocks cannot be reordered or swapped between files.
- `plainSize` in the authenticated header — truncation is detected. Without it, lopping
  bytes off the end would look like a shorter, valid file.
- `len(ct)` in the MAC — the final short block cannot be passed off as a full one.
- The nonce in the MAC — a block cannot be moved between two files sharing a key.

A damaged block fails only reads that touch it. Corruption in block 2 does not stop
block 0 from playing.

---

## 4. `.sonderlock` bundles

Sharing and backup use one format. A backup is a share of everything.

Each bundle carries its own **six-word code**, generated fresh. A code that leaks costs
that bundle and nothing else.

```
offset  size  field
     0     8  magic "SLBUNDL1"
     8     1  version = 1
     9     4  Argon2 memory, KiB, big endian
    13     4  Argon2 iterations, big endian
    17     1  Argon2 parallelism
    18    16  salt
    34    12  GCM nonce
    46     4  sealed manifest length, big endian
    50     -  sealed manifest, with bytes 0..49 as associated data
     -     -  entries, each a complete .slf container
```

The manifest holds one record per entry: original filename, media type, capture date,
byte length, offset within the entries region, container length, and the entry's file key.

Offsets are relative to the start of the entries region rather than to the file, and
they have to be: the manifest sits in front of the entries and its own length depends on
the offsets it contains, so absolute positions would be circular.

**Entries are copied across unchanged, not re-encrypted.** A container is already
encrypted under its own key, so exporting is a file copy rather than a second full pass of
AES over every photo. Restoring goes the other way — items are decrypted and written again
under fresh keys, so a bundle that leaks later, with its phrase, says nothing about the
vault it was restored into.

`bundleKey = Argon2id(phrase, salt)`. Six words from the BIP-39 English list is 66 bits;
against Argon2id at 64 MiB an offline search of that space is not a thing anyone does.

**Split above 3.5 GB** into numbered parts. Most SD cards ship FAT32, which caps a single
file at 4 GB.

### The honest part

"Only opens in SonderLock" is the file format, not enforcement. The source is public, so
anyone with the code and a copy of the repository can decrypt a bundle. The encryption is
what protects it; needing the app is convenience. This belongs in the README rather than
a claim to be stronger than it is.

---

## 5. Six-word codes

BIP-39 English, 2048 words, `sha256 2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`.

Six words, 11 bits each, 66 bits total. The phrase **is** the secret — it is not a
mnemonic for something else, and there is no other copy of it anywhere.

Chosen over a random character string for two reasons: it survives being written on
paper and typed back a year later, and BIP-39 words are unique in their first four
letters, so a typo is correctable without a dictionary attack on the user's spelling.

No checksum word. A wrong phrase fails at the GCM tag, which is the real check; a
checksum would only catch it slightly earlier, at the cost of a seventh word.

---

## 6. Parameters

| | |
|---|---|
| Argon2id | 64 MiB, t=3, p=2, 32-byte output |
| Content | AES-256-CTR + HMAC-SHA256 per block |
| Key wrapping | AES-256-GCM, 12-byte nonce, 16-byte tag |
| Derivation | HKDF-SHA256, expand only |
| Block size | 1 MiB (`blkLog2 = 20`) |

**Argon2id runs on the JVM via Bouncy Castle's lightweight API**, called directly rather
than registered as a JCE provider. No NDK, so CI stays a plain Gradle build. Android's
own bundled Bouncy Castle is repackaged under `com.android.org.bouncycastle` and does
not collide.

64 MiB is chosen to stay within reach of a low-end device while costing an attacker
real memory per guess. It is recorded in the slot header, so raising it later is a
re-wrap rather than a format change.
