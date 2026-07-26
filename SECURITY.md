# Security & Data Handling

Ledgerline Android is built to a zero-knowledge, privacy-by-default standard. This
document describes what the app stores, what it never stores, how data is
protected in transit and at rest, and how to revoke access. It doubles as the
GDPR/DSGVO transparency record for on-device data.

## What is stored on the device

Exactly one persisted secret: the **session blob**, written to a private DataStore
file as authenticated ciphertext (AES-256-GCM). It contains:

- the bearer token (proves identity to the server),
- the server base URL,
- the trust-on-first-use TLS SPKI pin.

The AES-256-GCM key lives only in the Android Keystore (StrongBox-backed where
available), is generated with `setUserAuthenticationRequired(true)`, and is
invalidated if the user enrolls a new biometric
(`setInvalidatedByBiometricEnrollment(true)`). The sealed blob is unreadable until
the user authenticates with a class-3 biometric or the device credential.

## What is NEVER stored

- **The Vault Key (VK)** — held in memory only, zeroed on background (`ON_STOP`)
  and after a 5-minute idle timeout. Never written to disk.
- **The passphrase** — used transiently to derive the KEK, then its byte/char
  buffers are zeroed.
- **The recovery code** — used transiently on the recovery path only.
- No plaintext files, caches, logs, or backups. The app emits no `Log`/`println`
  output containing secrets (verified in CI-style greps), and release builds strip
  debug logging via R8.

## Data in transit

- HTTPS only. Cleartext is blocked app-wide by the network security config, and
  the OkHttp client is restricted to `RESTRICTED_TLS` (TLS 1.2+, strong ciphers).
- **TOFU SPKI pinning**: at pairing the app records the server leaf certificate's
  SubjectPublicKeyInfo SHA-256 and enforces it on every later request via an
  OkHttp `CertificatePinner`. A swapped certificate — even one from a valid CA —
  is rejected.
- All payloads are opaque to the server: ciphertext blobs, client-sealed
  manifests, and public KDF parameters.

## Cryptography

Byte-compatible with the reference web client (`resources/js/vault.js`):

- KDF: `crypto_pwhash` with `ALG_ARGON2ID13`, 32-byte output, 16-byte salt, using
  the server-provided `kdf_ops` / `kdf_mem` (never hardcoded).
- Key wrap / metadata / manifests: `crypto_secretbox` (XSalsa20-Poly1305,
  24-byte nonce).
- Recovery key: keyless `crypto_generichash` (BLAKE2b-256) of the recovery bytes.
- Content blobs (later phases): `crypto_secretstream` XChaCha20-Poly1305, chunked.

## Application lock

A biometric / device-credential prompt is required immediately before the sealed
session is read or written (post-pairing seal, and pre-unlock load). The keystore
key uses a short time-bound auth window so a standard `BiometricPrompt` authorizes
the operation.

Future hardening: bind a `CryptoObject` to each key operation for per-use auth
instead of a time-bound window.

## Screen & backup protection

- `FLAG_SECURE` on the window blocks screenshots, screen recording, and the
  recents/task-switcher preview.
- `android:allowBackup="false"`, `fullBackupContent="false"`, and data-extraction
  rules exclude the app from cloud backup and device-to-device transfer.

## Permissions

Every permission is justified and — where dangerous — requested at runtime, in context,
and optional. The app uses **no** Google Play Services and performs **no** analytics or
telemetry.

- `INTERNET`, `ACCESS_NETWORK_STATE` — talk to your self-hosted server (HTTPS-only, SPKI-pinned).
- `USE_BIOMETRIC` — biometric-gated unlock / Keystore key use.
- `CAMERA` — QR pairing scan only (runtime-requested at pairing; ZXing decodes on-device, nothing recorded/uploaded).
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — **optional**, requested only when you take a photo, solely to geotag that shot. Never used for background tracking.
- `READ_CONTACTS`, `WRITE_CONTACTS` — **optional**, only to sync the encrypted address book with a local "Ledgerline" device account you explicitly enable. Nothing is uploaded anywhere.
- `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` — **optional**, only for camera-roll backup into the gallery.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` — keep long uploads/scans alive behind a visible notification (AOSP data-sync type; no Google).
- `POST_NOTIFICATIONS` — the foreground-service notification (Android 13+).

## Current security posture

Beyond the zero-knowledge core: Argon2id KDF with server-supplied bounds; libsodium
secretbox/secretstream; **suite-tagged, canonical-JSON, Padmé-padded** sealed manifests
(fail-closed on an unknown crypto suite); post-quantum hybrid KEM (X25519 + ML-KEM-768,
byte-verified) for sharing crypto; per-use `CryptoObject`-bound biometric Keystore keys
(StrongBox where available); an unlock throttle, always-on duress auto-wipe, monotonic
idle-lock, clock-rollback guard, and an encrypted local security audit log; `FLAG_SECURE`
app-wide; remote-wipe kill switch. The Vault Key lives in memory only and is zeroed on
background/idle and when replaced.

## Revocation

There is no token refresh by design. To revoke a device, remove it from the
Ledgerline web profile; the token stops working and the device must be re-paired
by scanning a new QR. Enrolling a new biometric on the device also invalidates the
keystore key, forcing a re-pair.

## Reporting

For security issues, contact the maintainer privately rather than filing a public
issue.
