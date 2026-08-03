# Ledgerline for Android

Native Android client for the self-hosted **Ledgerline** personal cloud — files,
gallery, a workspace (notes · todos · bookmarks · contacts), a password manager,
health, maps/tracks, and invoicing. Privacy-first, **100 % Google-free** (no Play
Services, no Firebase), and **zero-knowledge**: the server only ever stores
ciphertext.

The backend is a Laravel app exposing a versioned mobile API under `/api/v1`. Three
clients share the same wire contract and crypto: the **web app** (reference), a
native **iOS** client, and this **Android** app.

> **Status:** v0.9.x — feature-complete against the current server. Signed release
> APKs are published automatically to [GitHub Releases](../../releases) on every push
> to `main`.

---

## Zero-knowledge, in one paragraph

All encryption and decryption happen **on the device**. The server stores only
ciphertext blobs, client-sealed manifests, public KDF parameters, and a byte quota.
The bearer token proves **identity** only — it unlocks nothing. The Vault Key (VK) is
derived from your passphrase with **Argon2id** and never leaves the device; it lives
in memory only and is wiped on background and after an idle timeout. Everything below
is client-side crypto, byte-compatible with the reference web client
(`resources/js/vault.js`): libsodium `crypto_pwhash` (Argon2id13), `crypto_secretbox`,
`crypto_secretstream`, plus an ML-KEM-768 + X25519 hybrid KEM for cross-user sharing.

See [`SECURITY.md`](SECURITY.md) for the full threat model and the honestly-kept
gap register.

---

## Features

- **Files** — encrypted upload (chunked S3-multipart ≥ 64 MiB), folders, favourites,
  trash, versions, public share links.
- **Gallery** — encrypted photos/videos, on-device thumbnails, semantic (CLIP) search,
  faces/people, albums, map view, and an unlocked-only camera-roll **auto-backup**
  with an optional *delete-originals-after-backup* (recoverable trash, consent-gated).
- **Workspace** — Notes (Markdown), Todos (date + time due), Bookmarks, Contacts
  (vCard import/export, device address-book sync, de-duplication). Chip-based tags.
- **Passwords** — TOTP, strength + HIBP breach check, generator, favicons, 2FA hints,
  **Autofill** service, and **passkeys** (WebAuthn credential provider). Zero-knowledge:
  nothing leaves the device before you unlock.
- **Health** — weight/BP/pulse/SpO₂/temperature/glucose, intermittent-fasting timer,
  charts, CSV export.
- **Explore** — offline vector maps (mapsforge), a GPS tracker (hike/run/cycle) with a
  foreground service, GPX/KML import/export, elevation + calories.
- **Finance** — zero-knowledge invoices (GoBD gapless numbering, on-device PDF + e-mail
  send), transactions, payment methods, projects, partners, bank-statement import, VAT.
- **Sharing** — public share links (files/folders/gallery albums) and cross-user shared
  vaults with **post-quantum** hybrid-KEM key wrapping.
- **Account** — QR device pairing, connected-device management + remote wipe, login-2FA,
  data export, account deletion, per-app language (EN / DE / RU).

---

## Security highlights

- **Transit** — HTTPS enforced (cleartext blocked app-wide), `RESTRICTED_TLS`, and
  **TOFU SPKI pinning** captured at pairing, fail-closed.
- **At rest** — the only persisted secret is the session token, sealed with a
  hardware-backed AES-256-GCM Keystore key (StrongBox where available), gated by a
  per-use biometric `CryptoObject`. `allowBackup=false`.
- **Side channels** — `FLAG_SECURE` app-wide (screenshots / recents blocked), no
  secret logging, monotonic idle-lock, password-keyboard hardening.
- **Anti-coercion** — monotonic exponential unlock throttle, always-on duress
  auto-wipe, encrypted local security audit log, and a remote-wipe kill switch.
- **Post-quantum** — ML-KEM-768 (FIPS-203) + X25519 hybrid KEM, KAT-verified
  byte-exact against web + iOS.

> Visual changes can't be verified with `adb screencap` (it returns black due to
> `FLAG_SECURE`) — check them on a real device.

---

## Offline & data-safety

Ledgerline is offline-first, and the write path is built so **no edit is ever silently
lost**:

- The local cache is **ciphertext** (sealed manifests + blob bytes, exactly as stored
  by the server); it is decrypted in memory only, on access.
- Every store write is **optimistic** and **fetch-first**: it reflects immediately in
  the UI and re-reads the current server slice (content + version together) before
  sealing, so a version-matched PUT is only ever additive — never a silent clobber.
- On offline **or any recoverable server error** (5xx / 429 / exhausted 409), the edit
  is queued to a durable, VK-sealed **outbox** and replayed on reconnect (a
  `NetworkCallback` drains it the moment the network returns).
- Failed/offline **blob imports** (photos, files) are sealed to a durable on-disk queue
  and resumed on reconnect (ZK-clean — never plaintext at rest).
- **Version-history recovery**: the server retains recent sealed-root versions; a
  per-store "History" action can restore records lost to an older clobber.

Store shapes follow **Store v3**: content-addressed **sharded** stores for
files/gallery/notes/passwords/contacts/invoices (one edit re-seals only the touched
shard) and monolith module stores for todos/bookmarks.

---

## Architecture

```
ui/      Compose screens + ViewModels (MVVM, Material 3 Expressive)
domain/  models + use-cases (LoadWorkspace, MutateWorkspace, ImportPhotos, …)
data/    repositories, remote/LedgerlineApi (Retrofit), offline cache + outbox
core/    crypto, security, offline sync, backup, map, prefs
di/      Hilt modules
```

- **Crypto** — `com.goterl:lazysodium-android` + JNA (Argon2id on `Dispatchers.Default`,
  VK in memory), BouncyCastle for ML-KEM.
- **Maps** — mapsforge is the single map engine (offline `.map` vector + online OSM tile
  fallback); no Google Maps, no telemetry.
- **Background** — a while-alive `BackgroundSync` + `OfflineSyncEngine` (a truly
  app-closed job can't authenticate under the ZK model — the token is biometric-sealed).

---

## Tech stack

Kotlin · Jetpack Compose (Material 3 Expressive) · Coroutines / Flow · Hilt ·
Retrofit + OkHttp (bearer interceptor, SPKI pinning, 429 backoff) · lazysodium-android
+ JNA · BouncyCastle (FIPS-203 ML-KEM) · CameraX + ZXing (QR) · mapsforge · AndroidX
Credentials (passkeys) · DataStore · Android Keystore · BiometricPrompt.

`minSdk = 36 · targetSdk = 36 · compileSdk = 37` · package `de.ledgerline.app`.

Dependency policy: everything tracks the latest stable release (see the header of
`gradle/libs.versions.toml`); a few pins are documented in `CLAUDE.md §16`.

---

## Build

**Prerequisites**
- **JDK 17+** (CI uses 17; 21 also works locally). Point Gradle at it via `JAVA_HOME`
  or `org.gradle.java.home`.
- **Android SDK** with platform `android-37`. Put `sdk.dir=…` in `local.properties`
  (git-ignored).

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android Lint (errors baselined in lint-baseline.xml)
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (device/emulator)
./gradlew :app:installDebug           # install on a connected device
./gradlew :app:assembleRelease        # R8-minified, signed release APK
```

`versionCode` is derived from the git commit count (monotonic, CI-safe); `versionName`
is the marketing base in `app/build.gradle.kts` (`versionBase`).

### Release signing

Signing is opt-in via environment variables (or a `keystore.properties`):

```
LL_KEYSTORE_FILE       path to the .jks
LL_KEYSTORE_PASSWORD   store password
LL_KEY_ALIAS           key alias
LL_KEY_PASSWORD        key password
```

Without them, `assembleRelease` produces an **unsigned** APK.

---

## CI / releases (GitHub Actions)

- **`ci.yml`** — on push/PR: unit tests, Android Lint, `assembleDebug`, and an OSV
  vulnerable-dependency scan.
- **`release.yml`** — on push to `main`: builds a **signed** release APK and publishes
  it to a GitHub Release tagged `v<versionName>-<versionCode>`. Signing secrets
  (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`) live in the repo's
  Actions secrets.

---

## Pairing

1. In the Ledgerline web profile → "Connect device" shows a QR with
   `ledgerline://pair?url=<base_url>&code=<one-time-code>`.
2. The app scans it (or receives the deep link), claims the code, and polls until you
   approve the device in the web UI.
3. On approval the app receives a long-lived bearer token, captures the server's TLS
   SPKI pin, authenticates you (biometric), and seals the session to disk.
4. Tokens are long-lived and individually revocable — revoke/lose one → re-pair.

---

## Testing

JVM unit tests cover the crypto contract, the sharded-store engines, the offline
outbox + fetch-first clobber-safety, record codecs (no field loss), and domain logic.
On-device instrumented tests include **byte-exact interop KATs** against an independent
libsodium (PyNaCl) and the PQ-KEM against the FIPS-203 NIST KATs.

---

## Google-free / reproducibility note

Fully open source and Google-free. One deviation from strict F-Droid reproducibility:
`lazysodium-android` ships a prebuilt libsodium `.so`. Acceptable for self-hosted APK
distribution; a future F-Droid effort should build libsodium from source.

---

## Ground truth & docs

- Crypto: `../ledgerline/resources/js/vault.js` · PQ: `resources/js/shared/pq-kem.js`
- API: `../ledgerline/openapi.yaml` · `routes/api.php`
- iOS parity: `../ledgerline-ios/`
- Project build-plan & parity register: [`CLAUDE.md`](CLAUDE.md)
- Security model & gap register: [`SECURITY.md`](SECURITY.md)

## License

[MIT](LICENSE) © 2026 Malte Kiefer.
