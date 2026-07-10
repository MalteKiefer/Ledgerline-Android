# Ledgerline Android

Native Android client for the self-hosted [Ledgerline](https://home.kiefer-networks.de)
personal cloud (files, workspace, gallery). Privacy-first, zero Google
dependencies, and **zero-knowledge**: the server only ever stores ciphertext.

> Status: **Phase 1** — device pairing, secure token storage, application lock,
> transport pinning, and vault unlock. Files/gallery arrive in later phases.

## Zero-knowledge model

All encryption and decryption happen on the device. The server stores only
ciphertext blobs, client-sealed manifests, and public KDF parameters. The bearer
token proves **identity** only — it does not unlock anything. The Vault Key (VK)
is derived from your passphrase with Argon2id and never leaves the device (it
lives in memory only and is wiped on background/idle). Cryptography is
byte-compatible with the reference web client (`resources/js/vault.js`): libsodium
`crypto_pwhash` (Argon2id13), `crypto_secretbox`, `crypto_secretstream`.

## Phase 1 scope

- **Pairing** via `ledgerline://pair` deep link or in-app QR scan (CameraX +
  ZXing — no ML Kit / no Google Play Services).
- **Token at rest**: sealed with a hardware-backed AES-256-GCM key in the Android
  Keystore (StrongBox where available), gated behind biometric / device
  credential. No `EncryptedSharedPreferences` (deprecated).
- **App lock**: BiometricPrompt (class 3 + device credential) required before the
  sealed session is read or written.
- **TLS**: TLS 1.2+ only, cleartext blocked app-wide. Trust-on-first-use SPKI pin
  captured at pairing and enforced thereafter.
- **Vault unlock**: passphrase → Argon2id KEK → unwrap VK (recovery-code path
  included). VK in memory only, wiped on background and after a 5-minute idle
  timeout.
- **i18n**: English + German, per-app language.

See `docs/superpowers/specs/2026-07-10-phase1-pairing-security-design.md` for the
full design and `SECURITY.md` for the threat/data-handling model.

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Coroutines/Flow · Hilt · Retrofit +
OkHttp · CameraX + ZXing · lazysodium-android + JNA · DataStore · AndroidKeystore ·
BiometricPrompt. `minSdk = targetSdk = compileSdk = 36`.

## Build

Prerequisites:
- **JDK 21** (e.g. `brew install openjdk@21`). Point Gradle at it via
  `JAVA_HOME` or `org.gradle.java.home` in `~/.gradle/gradle.properties`.
- **Android SDK** with the `android-36` platform. Create `local.properties` with
  `sdk.dir=/path/to/Android/sdk` (git-ignored).

```sh
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (device/emulator)
./gradlew :app:assembleRelease      # R8-minified release APK
```

## Pairing flow

1. In the Ledgerline web profile, "Connect device" shows a QR containing
   `ledgerline://pair?url=<base_url>&code=<one-time-code>`.
2. The app scans it (or receives the deep link), `POST`s the code, and polls until
   you approve the device in the web UI.
3. On approval the app receives a long-lived bearer token, captures the server's
   TLS SPKI pin, authenticates you (biometric), and seals the session to disk.
4. Revoke a device from the web profile; a revoked token requires re-pairing.

## F-Droid / reproducibility note

The project is fully open source and Google-free. One deviation from strict
F-Droid reproducibility: `lazysodium-android` ships a prebuilt libsodium `.so`
(an F-Droid anti-feature). This is acceptable for the current self-hosted APK
distribution; a future F-Droid effort should build libsodium from source.

## License

TBD.
