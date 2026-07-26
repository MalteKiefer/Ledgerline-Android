# Regenerate app/src/androidTest/assets/crypto_kat.json — the byte-exact KAT fixtures
# used by CryptoKatTest to prove SodiumCrypto interoperates with an INDEPENDENT libsodium.
#
#   python3 -m venv venv && ./venv/bin/pip install pynacl
#   ./venv/bin/python tools/gen_crypto_kat.py > app/src/androidTest/assets/crypto_kat.json
#
# PyNaCl is a different binding to the same libsodium C library, so a match proves the
# Kotlin (lazysodium) wrapper's byte-mapping is correct. Deterministic — commit the output.

import json, base64, struct
import nacl.bindings as b
from nacl.pwhash import argon2id

def hx(bs): return bs.hex()

passphrase = b"correct horse battery staple"
salt = bytes(range(16)); ops = 2; mem = 67108864
vk = argon2id.kdf(32, passphrase, salt, opslimit=ops, memlimit=mem)

key = bytes(range(32)); nonce = bytes((i+7)&0xFF for i in range(24)); pt=b"vault-key-material"
cipher = b.crypto_secretbox(pt, nonce, key)

fk = bytes((i*3+1)&0xFF for i in range(32))
state = b.crypto_secretstream_xchacha20poly1305_state()
header = b.crypto_secretstream_xchacha20poly1305_init_push(state, fk)
TAG_FINAL = b.crypto_secretstream_xchacha20poly1305_TAG_FINAL
plain = bytes((i%251) for i in range(5000))
c = b.crypto_secretstream_xchacha20poly1305_push(state, plain, None, TAG_FINAL)
blob = header + struct.pack('<I', len(c)) + c
vk_stream = bytes((i+5)&0xFF for i in range(32)); fk_nonce = bytes((i+11)&0xFF for i in range(24))
fk_wrapped = b.crypto_secretbox(fk, fk_nonce, vk_stream)
encFileKey = json.dumps({"c":base64.b64encode(fk_wrapped).decode(),"n":base64.b64encode(fk_nonce).decode()}, separators=(",",":"))

print(json.dumps({
 "argon2id":{"passphrase":"correct horse battery staple","salt_hex":hx(salt),"ops":ops,"mem":mem,"vk_hex":hx(vk)},
 "secretbox":{"key_hex":hx(key),"nonce_hex":hx(nonce),"plaintext":"vault-key-material","cipher_hex":hx(cipher)},
 "secretstream":{"vk_hex":hx(vk_stream),"encFileKey":encFileKey,"blob_hex":hx(blob),"plaintext_hex":hx(plain)},
}, indent=2))
