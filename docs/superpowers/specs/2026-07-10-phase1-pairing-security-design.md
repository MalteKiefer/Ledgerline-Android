# Ledgerline Android — Phase 1 Design: Pairing, App Security & Vault Unlock

**Date:** 2026-07-10
**Status:** Approved (design)
**Scope:** Phase 1 of the native Android client for the self-hosted Ledgerline
personal cloud. Establishes device pairing, secure token storage, application
lock, transport hardening, and zero-knowledge vault unlock. Later phases (files,
gallery) build on this foundation.

Ground truth for cryptography is `resources/js/vault.js` in the `ledgerline`
web repo. The transport contract is frozen and documented in the project
`CLAUDE.md`. Where this document and `CLAUDE.md` disagree, the divergence is
called out explicitly with a rationale.

---

## 1. Decisions (locked)

| Area | Decision | Notes |
|---|---|---|
| minSdk | **36 (Android 16)** | Newest-only; guarantees StrongBox + modern Keystore auth APIs. Narrow device reach accepted (personal self-hosted app). |
| targetSdk / compileSdk | 36 | |
| App lock | **Biometric class 3 + device-credential fallback** | `BiometricPrompt`, `setInvalidatedByBiometricEnrollment(true)`. |
| Token at rest | **Own AndroidKeystore AES-256-GCM wrapper** | No Jetpack Security / Tink dependency (deprecated). |
| Distribution | **Self-hosted APK / own repo** | Relaxed reproducibility; allows lazysodium prebuilt `.so`. F-Droid deferred. |
| Phase 1 scope | **Includes vault unlock** | libsodium (lazysodium-android) pulled in now. |
| VK persistence | **Never persist** | In-memory only; wiped on background + idle timeout. |
| TLS policy | **Trust-on-first-use SPKI pin** | Pin captured at pairing; enforced afterwards. |
| Languages | **German + English**, system-follows | English source strings; per-app language switch. |
| DI | **Hilt** | |
| HTTP | **Retrofit + OkHttp** | |
| Idle auto-lock | **On background + 5 min idle, configurable** | Wipes VK from memory. |
| App ID / repo | `de.ledgerline.app` / `Ledgerline-Android` | |

---

## 2. Overrides vs CLAUDE.md (explicit)

1. **QR scanning library:** CLAUDE.md suggests *ML Kit Barcode*. ML Kit is a
   Google/GMS dependency and violates the hard "zero Google dependencies"
   constraint. **Replaced with ZXing** (`com.google.zxing:core`, Apache-2, pure
   Java, no GMS) decoding CameraX `ImageAnalysis` frames.
2. **Token storage:** CLAUDE.md mentions EncryptedSharedPreferences. Jetpack
   Security Crypto is deprecated/unmaintained (2024). **Replaced with an own
   AndroidKeystore AES-256-GCM wrapper** over encrypted DataStore/file.
3. **lazysodium `.so`:** ships a prebuilt native binary — an F-Droid
   anti-feature. Acceptable under the chosen self-hosted distribution; documented
   here so a future F-Droid effort knows to revisit (e.g. build libsodium from
   source).

Everything else follows CLAUDE.md byte-for-byte (crypto constants, transport
envelope, pairing endpoints).

---

## 3. Architecture

Single Gradle module, clean layered, MVVM with unidirectional data flow.
Kotlin + Jetpack Compose (Material 3) + Coroutines/Flow + Hilt.

```
ui/        Compose screens + ViewModels (StateFlow, immutable UiState)
domain/    use-cases + models — pure Kotlin, no Android imports, unit-testable
data/      repositories, Retrofit API, Keystore wrapper, crypto wrapper, DataStore
core/      crypto (libsodium), security (keystore/biometric), Result types
di/        Hilt modules
```

Constraints:
- `domain` has zero Android/framework dependencies.
- Cryptography sits behind a `Crypto` interface so it is swappable and fakeable
  in tests.
- All persisted state is authenticated-encrypted; nothing sensitive touches disk
  in cleartext.

---

## 4. Feature detail

### 4.1 Pairing

Two entry points to obtain `(baseUrl, code)`:
- **Deep link** `ledgerline://pair?url=<url-encoded base_url>&code=<one-time-code>`
  via a validated `intent-filter`.
- **In-app scan**: CameraX preview + `ImageAnalysis` → ZXing decode of the same
  deep-link URI.

Flow (state machine):
1. `Idle` → parse `url` + `code`. Reject non-HTTPS `url`.
2. `Claiming` → `POST {url}/api/v1/auth/pair` body `{ code, device_name }`
   (`device_name` = `Build.MODEL`, user-editable). Expect `{ status: "pending" }`.
3. `Polling` → `GET {url}/api/v1/auth/pair?code=<code>` every ~2 s.
   - `pending` → keep polling.
   - `approved` → receive `{ token, user }` (once). Persist, capture SPKI pin,
     transition `Paired`.
   - `410` (consumed/expired/denied/unknown) → `Failed(reason)`.
   - `429` → exponential backoff honoring `Retry-After`.
4. `Paired` → proceed to app-lock setup / vault unlock.

The one-time `code` is held only in memory during pairing; never persisted.

### 4.2 Token storage (own Keystore AES-256-GCM wrapper)

- AndroidKeystore key alias `ledgerline_token_key`:
  `KeyProperties.KEY_ALGORITHM_AES` / `BLOCK_MODE_GCM` / `ENCRYPTION_PADDING_NONE`,
  256-bit, `setIsStrongBoxBacked(true)` (guaranteed on API 36),
  `setUserAuthenticationRequired(true)`,
  `setInvalidatedByBiometricEnrollment(true)`.
- Sealed payload (AES-GCM, random 12-byte IV stored alongside ciphertext):
  `{ token, baseUrl, spkiPinSha256 }` → written to a DataStore/file. No plaintext
  variant is ever written.
- Read requires a successful `BiometricPrompt` auth (unlocks key use).

### 4.3 Application lock

- On cold start and after idle/background, show `BiometricPrompt`
  (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`).
- Success authorizes Keystore key use → decrypt token → app usable.
- Enrollment change invalidates the key → forces re-pair (token unrecoverable by
  design). This is surfaced to the user with a clear re-pair prompt.

### 4.4 Vault unlock (zero-knowledge)

- `GET /api/v1/vault`. If `{ configured: false }` → screen: "Set up your vault in
  the web app first." (Setup/rotate is web-only per CLAUDE.md §10.)
- If configured: prompt passphrase → derive KEK with Argon2id using **server-
  provided** `kdf_ops` / `kdf_mem` / `salt` (never hardcoded), on
  `Dispatchers.Default`.
- `VK = crypto_secretbox_open_easy(wrapped_vault_key, wrap_nonce, KEK)` — throws
  on wrong passphrase.
- **Recovery path:** `recoveryBytes = from_hex(code without spaces)`;
  `recoveryKey = crypto_generichash(32, recoveryBytes)`;
  `VK = secretbox_open(wrapped_vault_key_recovery, recovery_nonce, recoveryKey)`.
- VK kept in memory only (a holder cleared on lock). Passphrase `CharArray`/byte
  buffers zeroed immediately after KEK derivation.
- Auto-lock: wipe VK on `Lifecycle.Event.ON_STOP` and after 5 min inactivity
  (configurable in settings). Re-unlock requires passphrase again.

---

## 5. Crypto layer

`com.goterl:lazysodium-android` + `net.java.dev.jna:jna` (aar). Wrapper
`SodiumCrypto : Crypto` mirrors `vault.js` exactly:
- `deriveKek(passphrase, salt, ops, mem)` → `crypto_pwhash` with
  `crypto_pwhash_ALG_ARGON2ID13`, outlen `crypto_secretbox_KEYBYTES` (32).
- `seal(data, key)` → 24-byte random nonce + `crypto_secretbox_easy`, base64
  `ORIGINAL` (padded).
- `open(cipher, nonce, key)` → `crypto_secretbox_open_easy`.
- `genericHash32(bytes)` for the recovery key.

Phase 1 uses KEK + secretbox_open + generichash only. `secretstream`,
`sealManifest/openManifest`, and Padmé padding are stubbed with signatures for
Phases 2–3. Byte-parity unit tests validate against fixed vectors derived from
`vault.js` behavior.

---

## 6. Networking

- Retrofit + OkHttp. Base URL is dynamic (from pairing), provided per session.
- Interceptors:
  - **Auth**: add `Authorization: Bearer <token>` to `/api/v1` calls (not to the
    public `/auth/pair` claim/poll).
  - **Retry/backoff**: exponential backoff on `429`, honoring `Retry-After`.
  - **TLS**: min TLS 1.3 connection spec.
- **TOFU SPKI pinning**: at pairing, capture the server leaf certificate
  SPKI-SHA256 and persist it in the sealed payload. All later requests enforce it
  via a custom `X509TrustManager` (or OkHttp `CertificatePinner` seeded from the
  stored pin). Mismatch → hard failure + "server identity changed, re-pair"
  prompt.
- `NetworkSecurityConfig`: `cleartextTrafficPermitted=false` app-wide; HTTPS only.

---

## 7. Security hardening checklist

- `FLAG_SECURE` on every Activity/window (blocks screenshots, screen recording,
  recents preview).
- Manifest: `android:allowBackup="false"`, `fullBackupContent="false"`,
  `fullBackupOnly="false"`, excluded from D2D transfer.
- No sensitive data in logs. Logging stripped in release via R8; a lint/CI check
  forbids logging VK/passphrase/token.
- VK wiped on `ON_STOP` and idle; passphrase buffers zeroed after use.
- Exported components minimized; the `ledgerline://pair` intent-filter validates
  scheme/host and rejects malformed input.
- Runtime permissions (minimum): `CAMERA` (pairing scan, requested in context),
  `USE_BIOMETRIC`, `INTERNET`. No storage/location perms in Phase 1.
- No hardcoded secrets. Assume the APK is reverse-engineered; client hardening is
  defense-in-depth, not a trust anchor (server enforces auth).

---

## 8. Internationalization

- All user-facing strings in `res/values/strings.xml` (English source) +
  `res/values-de/strings.xml` (German). Plurals via `<plurals>`.
- `locales_config.xml` + per-app language support (Android 13+; guaranteed on
  minSdk 36). RTL-ready layouts.

---

## 9. Testing

- **Unit:** crypto byte-parity (KEK/secretbox/generichash) against known vectors;
  pairing state machine transitions; token seal/unseal; SPKI pin extraction.
- **Instrumented:** Keystore key generation + AES-GCM round-trip;
  `BiometricPrompt` success/retry/lockout paths; deep-link parsing.
- Fakes for `Crypto` and network in domain/use-case tests (no real libsodium in
  pure unit layer).

---

## 10. Tooling, prerequisites & build

- **Blocker 1 — JDK:** no Java runtime on PATH. Requires JDK 17 or 21 for the
  Android Gradle Plugin. Must be installed/resolved before first build.
- **Blocker 2 — emulator image:** the existing `Pixel_9a` AVD must run an
  **API 36** system image to match minSdk 36; install if absent.
- Android SDK present at `~/Library/Android/sdk` (`adb`, emulator, cmdline-tools).
- Gradle (Kotlin DSL), version catalog for dependencies.
- Git: initialize `Ledgerline-Android`, Git Flow branches
  (`main` / `develop` / `feature/*`), Conventional Commits, **English only** in
  all commits/branches/code/docs, strict `.gitignore` (no keystores, secrets,
  `.env`, local props).

---

## 11. Out of scope (Phase 1)

Files/workspace store, gallery, uploads/downloads, blob crypto (secretstream),
Padmé padding, offline cache, encrypted export, push. Deferred to later phases
(Workspace store → File blobs → Gallery → Polish/export).
