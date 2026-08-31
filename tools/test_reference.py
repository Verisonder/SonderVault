import os
import struct
import sys

import reference as r

ok, fail = 0, 0


def check(name, cond):
    global ok, fail
    if cond:
        ok += 1
        print(f"  pass  {name}")
    else:
        fail += 1
        print(f"  FAIL  {name}")


print("\n=== key slots ===")
real_master = os.urandom(32)
decoy_master = os.urandom(32)
blob = r.build_slots([
    (b"correct horse", r.VAULT_REAL, 0, real_master),
    (b"duress phrase", r.VAULT_DECOY, r.FLAG_WIPE, decoy_master),
])

check("file is a fixed 302 bytes regardless of how many slots are used",
      len(blob) == r.HEADER_SIZE + r.SLOT_COUNT * r.SLOT_SIZE == 302)

a = r.open_slots(blob, b"correct horse")
check("real password unwraps the real master key",
      a and a["masterKey"] == real_master and a["vaultId"] == r.VAULT_REAL and not a["wipe"])

b = r.open_slots(blob, b"duress phrase")
check("duress password unwraps the decoy vault with the wipe flag set",
      b and b["masterKey"] == decoy_master and b["vaultId"] == r.VAULT_DECOY and b["wipe"])

check("wrong password opens nothing", r.open_slots(blob, b"wrong") is None)

# the whole point of fixed-size random filler: an unused slot must not be
# distinguishable from a used one by anything an inspector can measure
one = r.build_slots([(b"only one", r.VAULT_REAL, 0, os.urandom(32))])
check("a vault with no duress password is the same size as one with", len(one) == len(blob))

tampered = bytearray(blob)
tampered[r.HEADER_SIZE + 30] ^= 0x01
check("flipping a bit in a slot makes it unopenable",
      r.open_slots(bytes(tampered), b"correct horse") is None
      or r.open_slots(bytes(tampered), b"duress phrase") is None)

hdr_tampered = bytearray(blob)
hdr_tampered[5] ^= 0x01  # argon memory parameter
check("editing the header is caught (header is GCM associated data)",
      r.open_slots(bytes(hdr_tampered), b"correct horse") is None)


print("\n=== vault file: round trip and seeking ===")
BLK = 12  # 4 KiB blocks, so multi-block behaviour is testable at sane sizes
key = os.urandom(32)
plain = os.urandom(4096 * 3 + 777)  # three full blocks and a short one
enc = r.encrypt_file(key, plain, blk_log2=BLK)

expected = r.SLF_HEADER_SIZE + 4 * r.MAC_SIZE + len(plain)
check("container size is header + payload + one MAC per block", len(enc) == expected)
check("ciphertext never contains the plaintext", plain[:64] not in enc)
check("full read round trips", r.read_file(key, enc) == plain)

seeks = [0, 1, 15, 16, 17, 4095, 4096, 4097, 8191, 8192, 12287, 12288, 12500, len(plain) - 1]
bad = [o for o in seeks if r.read_file(key, enc, o, 200) != plain[o:o + 200]]
check(f"reads from {len(seeks)} offsets across every block boundary match", not bad)

check("a read clamps at the end of the file",
      r.read_file(key, enc, len(plain) - 10, 500) == plain[-10:])
check("a one byte read at the last byte is correct",
      r.read_file(key, enc, len(plain) - 1, 1) == plain[-1:])

# seeking must not read blocks it does not need: block 2 is corrupted, and a
# read confined to block 0 must still succeed
corrupt = bytearray(enc)
blk_stored = 4096 + r.MAC_SIZE
corrupt[r.SLF_HEADER_SIZE + 2 * blk_stored + 10] ^= 0xFF
try:
    got = r.read_file(key, bytes(corrupt), 0, 100)
    check("a read inside block 0 ignores damage in block 2", got == plain[:100])
except ValueError:
    check("a read inside block 0 ignores damage in block 2", False)

try:
    r.read_file(key, bytes(corrupt), 8192, 100)
    check("reading the damaged block is refused", False)
except ValueError:
    check("reading the damaged block is refused", True)

truncated = enc[:-40]
try:
    r.read_file(key, truncated)
    check("a truncated file is refused", False)
except ValueError:
    check("a truncated file is refused", True)

lied = bytearray(enc)
lied[14:22] = struct.pack(">Q", len(plain) + 1000)
try:
    r.read_file(key, bytes(lied))
    check("editing the declared size is refused", False)
except ValueError:
    check("editing the declared size is refused", True)

check("an empty file writes no blocks",
      len(r.encrypt_file(key, b"", blk_log2=BLK)) == r.SLF_HEADER_SIZE)
check("an empty file round trips", r.read_file(key, r.encrypt_file(key, b"", blk_log2=BLK)) == b"")

exact = os.urandom(4096 * 2)
check("a file that is an exact multiple of the block size round trips",
      r.read_file(key, r.encrypt_file(key, exact, blk_log2=BLK)) == exact)

other = os.urandom(32)
try:
    r.read_file(other, enc)
    check("the wrong file key is refused", False)
except ValueError:
    check("the wrong file key is refused", True)


print("\n=== six word codes ===")
words = r.phrase_generate()
check("six words are produced", len(words) == 6)
check("every word is in the list", all(w in r.WORDS for w in words))
check("two generated phrases differ", r.phrase_generate() != r.phrase_generate())

salt = os.urandom(16)
check("the same phrase derives the same key",
      r.phrase_to_key(words, salt) == r.phrase_to_key(words, salt))
check("case and spacing do not matter",
      r.phrase_to_key([w.upper() for w in words], salt) == r.phrase_to_key(words, salt))
check("a different salt gives a different key",
      r.phrase_to_key(words, os.urandom(16)) != r.phrase_to_key(words, salt))

check("BIP-39 words are unique in their first four letters",
      len({w[:4] for w in r.WORDS}) == 2048)
check("a truncated word is corrected", r.phrase_normalise("abanmisspelled") == ["abandon"])
check("a word not on the list is rejected", r.phrase_normalise("zzzz qqqq") is None)
check("a correct phrase normalises to itself", r.phrase_normalise(" ".join(words)) == words)

print(f"\n{ok} passed, {fail} failed")
sys.exit(1 if fail else 0)
