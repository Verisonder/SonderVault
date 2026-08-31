"""
SonderVault format reference implementation.

Not shipped. Exists to prove the byte layouts and the seek arithmetic before any
Kotlin is written, and to emit test vectors the Kotlin side must reproduce.
"""

import hashlib
import hmac
import os
import struct

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# ---------------------------------------------------------------- primitives

ARGON_MEM_KIB = 65536
ARGON_ITERS = 3
ARGON_PAR = 2


def argon2id(password: bytes, salt: bytes) -> bytes:
    return hash_secret_raw(
        secret=password, salt=salt,
        time_cost=ARGON_ITERS, memory_cost=ARGON_MEM_KIB,
        parallelism=ARGON_PAR, hash_len=32, type=Type.ID,
    )


def hkdf(key: bytes, info: bytes, length: int = 32) -> bytes:
    """HKDF-SHA256, extract skipped: the input is already a uniform 256-bit key."""
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(key, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def gcm_seal(key: bytes, nonce: bytes, plain: bytes, aad: bytes = b"") -> bytes:
    enc = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    enc.authenticate_additional_data(aad)
    return enc.update(plain) + enc.finalize() + enc.tag


def gcm_open(key: bytes, nonce: bytes, blob: bytes, aad: bytes = b"") -> bytes | None:
    ct, tag = blob[:-16], blob[-16:]
    dec = Cipher(algorithms.AES(key), modes.GCM(nonce, tag)).decryptor()
    dec.authenticate_additional_data(aad)
    try:
        return dec.update(ct) + dec.finalize()
    except Exception:
        return None


def ctr_at(key: bytes, nonce8: bytes, counter: int, data: bytes) -> bytes:
    """AES-256-CTR with a 16-byte counter block: nonce(8) || counter(8, big endian)."""
    iv = nonce8 + struct.pack(">Q", counter)
    c = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    return c.update(data) + c.finalize()


# ------------------------------------------------------------ key slot file
#
# slots.bin — fixed size, always four slots, unused slots filled with random
# bytes so their absence is indistinguishable from their presence.
#
#   magic     "SVK1"      4
#   version   u8          1
#   memKiB    u32 be      4
#   iters     u32 be      4
#   par       u8          1
#   salt                 16      one salt for the file: one Argon2 run per attempt
#   slot[4]              68 each
#       nonce            12
#       sealed           56      AES-256-GCM of a 40-byte payload + 16-byte tag
#
# payload (40 bytes, fixed so every slot is the same size):
#   vaultId   u8          0 = real, 1 = decoy
#   flags     u8          bit0 = wipe the real vault on unlock
#   reserved  6           random
#   masterKey 32

SLOT_COUNT = 4
SLOT_SIZE = 68
HEADER_SIZE = 4 + 1 + 4 + 4 + 1 + 16
PAYLOAD_SIZE = 40

VAULT_REAL, VAULT_DECOY = 0, 1
FLAG_WIPE = 0x01


def slots_header(salt: bytes) -> bytes:
    return (b"SVK1" + bytes([1])
            + struct.pack(">I", ARGON_MEM_KIB)
            + struct.pack(">I", ARGON_ITERS)
            + bytes([ARGON_PAR]) + salt)


def build_slots(entries):
    """entries: list of (password, vaultId, flags, masterKey). Order is randomised."""
    salt = os.urandom(16)
    header = slots_header(salt)
    slots = []
    for password, vault_id, flags, master in entries:
        kek = argon2id(password, salt)
        payload = bytes([vault_id, flags]) + os.urandom(6) + master
        assert len(payload) == PAYLOAD_SIZE
        nonce = os.urandom(12)
        slots.append(nonce + gcm_seal(kek, nonce, payload, aad=header))
    while len(slots) < SLOT_COUNT:
        slots.append(os.urandom(SLOT_SIZE))
    order = list(range(SLOT_COUNT))
    # shuffle so the real slot is not always first
    for i in range(SLOT_COUNT - 1, 0, -1):
        j = int.from_bytes(os.urandom(2), "big") % (i + 1)
        order[i], order[j] = order[j], order[i]
    return header + b"".join(slots[k] for k in order)


def open_slots(blob: bytes, password: bytes):
    header = blob[:HEADER_SIZE]
    assert header[:4] == b"SVK1"
    salt = header[-16:]
    kek = argon2id(password, salt)  # exactly one Argon2 run per attempt
    for i in range(SLOT_COUNT):
        off = HEADER_SIZE + i * SLOT_SIZE
        nonce = blob[off:off + 12]
        sealed = blob[off + 12:off + SLOT_SIZE]
        payload = gcm_open(kek, nonce, sealed, aad=header)
        if payload is not None:
            return {"slot": i, "vaultId": payload[0],
                    "wipe": bool(payload[1] & FLAG_WIPE), "masterKey": payload[8:40]}
    return None


# --------------------------------------------------------------- vault file
#
# .slf — one per stored item. Seekable, so video scrubs without decrypting
# everything before the seek point. CTR for seekability, encrypt-then-MAC per
# block for integrity, because one MAC over a 2 GB file means reading all of it
# before showing the first frame.
#
#   magic       "SVF1"    4
#   version     u8        1
#   blkLog2     u8        1     20 = 1 MiB
#   nonce                 8
#   plainSize   u64 be    8     authenticated, so truncation is detected
#   headerMac             32    HMAC-SHA256(macKey, the 22 bytes above)
#   then per block: ciphertext (blkSize, last short) || blockMac (32)

SLF_HEADER_SIZE = 4 + 1 + 1 + 8 + 8 + 32
MAC_SIZE = 32


def file_keys(file_key: bytes):
    return hkdf(file_key, b"sondervault:enc:v1"), hkdf(file_key, b"sondervault:mac:v1")


def block_mac(mac_key: bytes, nonce: bytes, index: int, ct: bytes) -> bytes:
    m = hmac.new(mac_key, digestmod=hashlib.sha256)
    m.update(nonce)
    m.update(struct.pack(">Q", index))
    m.update(struct.pack(">Q", len(ct)))
    m.update(ct)
    return m.digest()


def encrypt_file(file_key: bytes, plain: bytes, blk_log2: int = 20) -> bytes:
    enc_key, mac_key = file_keys(file_key)
    blk = 1 << blk_log2
    nonce = os.urandom(8)
    head = b"SVF1" + bytes([1, blk_log2]) + nonce + struct.pack(">Q", len(plain))
    out = [head, hmac.new(mac_key, head, hashlib.sha256).digest()]
    index, off = 0, 0
    while off < len(plain):
        chunk = plain[off:off + blk]
        # the counter is global across the file, so a block boundary is also a
        # counter boundary and seeking never has to replay earlier blocks
        ct = ctr_at(enc_key, nonce, index * (blk // 16), chunk)
        out.append(ct)
        out.append(block_mac(mac_key, nonce, index, ct))
        off += blk
        index += 1
    return b"".join(out)


def read_file(file_key: bytes, blob: bytes, offset: int = 0, length: int | None = None) -> bytes:
    """Decrypt from an arbitrary plaintext offset, touching only the blocks needed."""
    enc_key, mac_key = file_keys(file_key)
    head = blob[:22]
    if head[:4] != b"SVF1":
        raise ValueError("not a vault file")
    if not hmac.compare_digest(blob[22:22 + MAC_SIZE],
                               hmac.new(mac_key, head, hashlib.sha256).digest()):
        raise ValueError("header failed authentication")
    blk_log2 = head[5]
    blk = 1 << blk_log2
    nonce = head[6:14]
    plain_size = struct.unpack(">Q", head[14:22])[0]

    if length is None:
        length = plain_size - offset
    length = max(0, min(length, plain_size - offset))
    if length == 0:
        return b""

    first, last = offset // blk, (offset + length - 1) // blk
    out = bytearray()
    for index in range(first, last + 1):
        stored = blk + MAC_SIZE
        pos = SLF_HEADER_SIZE + index * stored
        ct_len = min(blk, plain_size - index * blk)
        ct = blob[pos:pos + ct_len]
        tag = blob[pos + ct_len:pos + ct_len + MAC_SIZE]
        if not hmac.compare_digest(tag, block_mac(mac_key, nonce, index, ct)):
            raise ValueError(f"block {index} failed authentication")
        out += ctr_at(enc_key, nonce, index * (blk // 16), ct)
    start = offset - first * blk
    return bytes(out[start:start + length])


# ------------------------------------------------------------ six-word codes

# The shipped wordlist, wherever this is run from. Keeping a second copy beside the
# script is how the two quietly drift apart.
_WORDLIST = next(
    p for p in (
        "bip39_en.txt",
        "../app/src/main/res/raw/bip39_en.txt",
        "app/src/main/res/raw/bip39_en.txt",
    ) if os.path.exists(p)
)

with open(_WORDLIST) as fh:
    WORDS = [w.strip() for w in fh if w.strip()]
assert len(WORDS) == 2048


def phrase_generate(n: int = 6):
    """66 bits for six words. The phrase is the secret; there is no other copy."""
    idx = []
    for _ in range(n):
        # rejection-free: 11 bits taken from two fresh bytes
        idx.append(int.from_bytes(os.urandom(2), "big") & 0x7FF)
    return [WORDS[i] for i in idx]


def phrase_to_key(words, salt: bytes) -> bytes:
    return argon2id(" ".join(w.lower() for w in words).encode(), salt)


def phrase_normalise(typed: str):
    """BIP-39 words are unique in their first four letters, which is what makes
    typo correction cheap and reliable."""
    prefixes = {w[:4]: w for w in WORDS}
    out = []
    for raw in typed.lower().split():
        if raw in WORDS:
            out.append(raw)
        elif len(raw) >= 4 and raw[:4] in prefixes:
            out.append(prefixes[raw[:4]])
        else:
            return None
    return out
