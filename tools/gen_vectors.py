"""Emit deterministic vectors. The Kotlin implementation must reproduce these
byte for byte; if it does not, one of the two is wrong and the test says which."""

import hashlib
import json
import os

import reference as r

# deterministic stand-in for os.urandom so the vectors are reproducible
_state = [0]


def fake_urandom(n):
    out = b""
    while len(out) < n:
        out += hashlib.sha256(b"sonderlock-vectors" + _state[0].to_bytes(4, "big")).digest()
        _state[0] += 1
    return out[:n]


os.urandom = fake_urandom
r.os.urandom = fake_urandom

h = lambda b: b.hex()
v = {}

master = bytes(range(32))
salt = bytes([0xA5] * 16)
password = b"correct horse battery staple"

v["argon2id"] = {
    "note": "Argon2id, 64 MiB, t=3, p=2, 32 byte output",
    "password": password.decode(), "salt": h(salt),
    "memKiB": r.ARGON_MEM_KIB, "iters": r.ARGON_ITERS, "par": r.ARGON_PAR,
    "expected": h(r.argon2id(password, salt)),
}

file_key = bytes([0x11] * 32)
enc_key, mac_key = r.file_keys(file_key)
v["hkdf"] = {
    "note": "HKDF-SHA256 expand only; the input is already a uniform 256 bit key",
    "fileKey": h(file_key),
    "encKey": h(enc_key), "macKey": h(mac_key),
}

for name, size, blk in [("empty", 0, 12), ("short", 100, 12), ("exact_block", 4096, 12),
                        ("multi_block", 4096 * 2 + 333, 12)]:
    plain = bytes((i * 37 + 11) & 0xFF for i in range(size))
    _state[0] = 7  # fixed nonce per case
    blob = r.encrypt_file(file_key, plain, blk_log2=blk)
    v[f"vaultfile_{name}"] = {
        "blkLog2": blk, "plainSize": size,
        "plainSha256": h(hashlib.sha256(plain).digest()),
        "containerSize": len(blob),
        "containerSha256": h(hashlib.sha256(blob).digest()),
        "header": h(blob[:r.SLF_HEADER_SIZE]),
    }

_state[0] = 99
plain = bytes((i * 37 + 11) & 0xFF for i in range(4096 * 2 + 333))
blob = r.encrypt_file(file_key, plain, blk_log2=12)
v["vaultfile_seeks"] = {
    "containerSha256": h(hashlib.sha256(blob).digest()),
    "reads": [{"offset": o, "length": n, "expectedSha256":
               h(hashlib.sha256(plain[o:o + n]).digest())}
              for o, n in [(0, 16), (4095, 2), (4096, 100), (8192, 333), (5000, 4000)]],
}

_state[0] = 1000
slots = r.build_slots([
    (b"real-password", r.VAULT_REAL, 0, master),
    (b"duress-password", r.VAULT_DECOY, r.FLAG_WIPE, bytes(range(32, 64))),
])
v["slots"] = {
    "size": len(slots), "sha256": h(hashlib.sha256(slots).digest()),
    "blob": h(slots),
    "cases": [
        {"password": "real-password", "vaultId": 0, "wipe": False, "masterKey": h(master)},
        {"password": "duress-password", "vaultId": 1, "wipe": True,
         "masterKey": h(bytes(range(32, 64)))},
        {"password": "not-a-password", "opens": False},
    ],
}

v["wordlist"] = {
    "source": "BIP-39 English",
    "count": len(r.WORDS),
    "sha256": "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
    "first": r.WORDS[0], "last": r.WORDS[-1], "index_1023": r.WORDS[1023],
}

phrase = ["abandon", "zoo", "legal", "winner", "thank", "yellow"]
v["phrase"] = {
    "words": phrase, "salt": h(salt),
    "key": h(r.phrase_to_key(phrase, salt)),
    "normalise": {"input": "ABANdon  Zoo lega1x legal winner thank yellow", "note":
                  "words are matched on their unique first four letters"},
}

print(json.dumps(v, indent=2))
