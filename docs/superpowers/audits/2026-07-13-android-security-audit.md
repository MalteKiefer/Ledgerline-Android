# Ledgerline Android — Security & Quality Audit

**Date:** 2026-07-13 · **Scope:** whole `ledgerline-android` repo (Kotlin/Compose/Hilt),
build pipeline, Gradle config, dependencies. **Method:** 4 parallel read-only audit passes
(crypto/TLS, privacy/leaks, Kotlin quality/concurrency, dependencies/build) + Android Lint
(`lintDebug`, exit 0, no vital errors).

**Verdict:** No CRITICAL or HIGH issues in the security core. The zero-knowledge crypto,
key lifecycle, transport, and data-at-rest/logging model are implemented correctly and
byte-accurate against the web `vault.js` contract. The real gaps are **supply-chain /
build hygiene** (no CI, unverified toolchain, an alpha biometric lib) and **maintainability**
(a few structured-concurrency and DRY items). Nothing leaks plaintext, secrets, metadata,
or operations.

---

## HIGH

- **[HIGH] No CI/CD pipeline** — no `.github/workflows`, no automated build/test/lint/dep-scan.
  Regressions and vulnerable transitive deps go undetected until a manual build. **Fix:** add a
  GitHub Actions workflow running `./gradlew testDebugUnitTest lintDebug assembleDebug` on PRs,
  plus an OSV-Scanner / Dependency-Review step. Single highest-value action — it makes every
  other upgrade safe to do iteratively.
- **[HIGH] Gradle wrapper not integrity-pinned** — `gradle/wrapper/gradle-wrapper.properties`
  has no `distributionSha256Sum`. A poisoned cache/MITM can substitute build tooling. **Fix:**
  add `distributionSha256Sum=<sha>` from gradle.org/release-checksums for the pinned Gradle, and
  update on each bump.
- **[HIGH] No dependency verification** — no `gradle/verification-metadata.xml`; ~50 Maven
  artifacts fetched without checksum/PGP verification (dependency-confusion / mirror-poisoning
  surface). **Fix:** `./gradlew --write-verification-metadata pgp,sha256 --export-keys help` and
  commit the result.
- **[HIGH] Biometric library is an alpha in the vault-unlock path** — `gradle/libs.versions.toml`
  `biometric = "1.2.0-alpha05"` guards the unlock. Alpha = no stability/security guarantee.
  **Fix:** pin `androidx.biometric:biometric:1.1.0` (stable); revisit `biometric-compose` only
  once it ships stable.

## MEDIUM

- **[MEDIUM] Toolchain ~1 major behind (interdependent)** — AGP 8.7.3→9.2.0, Kotlin 2.0.21→2.4.0,
  KSP 2.0.21-1.0.28 (KSP1 is deprecated, unsupported on Kotlin ≥2.3 / AGP ≥9), Gradle 8.11.1→9.6.1.
  These upgrade together. **Fix:** bump AGP+Gradle+Kotlin+KSP in lockstep after CI exists; run the
  full test suite. `gradle/libs.versions.toml`.
- **[MEDIUM] Unmanaged coroutine scopes in singletons** — `BackgroundSync.kt:41`,
  `OfflinePrefs.kt:54`, `GalleryBackupManager.kt` each create a never-cancelled
  `CoroutineScope(SupervisorJob()+Dispatchers.Default)`. Process-scoped by intent, but leaks in
  tests and violates structured-concurrency hygiene. **Fix:** inject a single `@ApplicationScope
  CoroutineScope` via Hilt (mirror how `OperationManager` documents its test-dispatcher seam) and
  use it everywhere.
- **[MEDIUM] `runBlocking` on the main thread at startup** — `MainActivity.kt:61`,
  `ShareActivity.kt:69` (`settingsStore.timeoutMinutes.first()`), plus `OfflinePrefs`/`OperationManager`
  init seed DataStore with `runBlocking`. Potential cold-start ANR on slow devices / DI-graph
  deadlock risk. **Fix:** seed with a safe default and update asynchronously via `lifecycleScope`/a
  collector.
- **[MEDIUM] `UnlockVault` force-unwraps nullable vault fields** — `UnlockVault.kt:29-30,44`
  (`v.salt!!`, `v.kdfOps!!`, …). A malformed server response surfaces as an opaque
  `Outcome.Err(DECRYPT)` (KotlinNullPointerException swallowed). **Fix:** null-check the critical
  fields and return a dedicated `MALFORMED_VAULT`/`NOT_CONFIGURED` error, or make them non-null by
  contract when `configured=true`.
- **[MEDIUM] Duplicated repository logic (DRY)** — `WorkspaceRepository.save()` and
  `GalleryRepository.save()` are the same optimistic-409-retry loop; `FileBlobRepository.deleteBlobs()`
  and `GalleryBlobRepository.deleteBlobs()` are the same 429-backoff loop (files variant misses the
  blank/distinct filter); both repos have near-identical `cachedOr()`. **Fix:** extract a generic
  `optimisticSave<M>` and `deleteWithBackoff(api, ids)` helper.
- **[MEDIUM] osmdroid archived (Nov 2024)** — `osmdroid 6.1.20` gets no security/API-level fixes.
  **Fix:** plan migration to MapLibre Android (active, OSM-compatible, non-Google) — this is the
  larger future item.
- **[MEDIUM] No release `signingConfig`** — `app/build.gradle.kts` has no `signingConfigs`; release
  would fall back to the debug key. **Fix:** add a `signingConfigs.release` reading store/key from
  env vars or gitignored `local.properties` (never committed) and wire into `buildTypes.release`.
- **[MEDIUM] `reconcile()` implemented but unwired** — `ContactBlobRepository.reconcile()` has no
  callers; `files`/`gallery` reconcile endpoints aren't even declared in `LedgerlineApi.kt` despite
  the spec listing them. Dead-in-the-making or a real gap (orphaned blobs never garbage-collected).
  **Fix:** either wire reconcile into the delete/trash flows or mark it `// deferred — Phase N` and
  remove the unused method.
- **[MEDIUM] Stale libs (fixes only)** — Media3 1.5.1→1.10.1, CameraX 1.4.1→1.6.0, Robolectric
  4.15.1→4.16.1 (4.16 adds SDK 36 → removes the `@Config(sdk=[35])` + `isIncludeAndroidResources=false`
  workarounds), Hilt 2.52→2.57, navigation/activity/lifecycle/coroutines/serialization/datastore all
  a few minors behind. **Fix:** batch-bump post-CI. No CVEs found in current pins (OSV/GitHub Advisory).

## LOW

- **[LOW] Test-only code in the shipping `main` source set** — `allowCleartext=true` `forTest`
  factories (`FileBlobRepository.kt:223`, `GalleryBlobRepository.kt:158`, `ContactBlobRepository.kt:153`)
  plus the `internal` cleartext `NetworkFactory` overload; `UnusedCrypto`/`NoOfflineFlags`
  (`FileBlobRepository.kt:206-254`); `SodiumCrypto.secretBoxSealForTest`; `GalleryUploader` `open` for
  test subclassing. Not exploitable (cleartext blocked by `network_security_config`) but unnecessary
  attack surface + bloat. **Fix:** move to `src/test`/`testFixtures`, or inject seams (e.g. a
  `Base64Decoder` lambda) instead of `open`.
- **[LOW] Key-array hygiene** — recovery bytes from `fromHex` (`SodiumCrypto.kt:57-62`) and the
  per-blob file keys `fk` in `newContentEncryptor`/`contentDecryptor` (`:110,:139-143`) aren't zeroed
  after use. VK itself is correctly wiped. **Fix:** zero these arrays on close.
- **[LOW] TOFU pin capture uses a second connection** — `PairingRepository.kt:54-63` captures the
  SPKI from a fresh unpinned handshake, not the one that returned the token. **Fix:** read
  `response.handshake` from the poll response that carried `approved`.
- **[LOW] Two base64 implementations** — `PinnedTrust.spkiSha256Base64()` uses `java.util.Base64`
  while `SodiumCrypto` uses `android.util.Base64`. **Fix:** route through the injected `Crypto.b64*`
  seam for one source of truth.
- **[LOW] Scattered force-unwraps** — `GalleryScreen.kt:435` (`openInputStream(uri)!!`),
  `GalleryMapScreen.kt:102,115,122`, `PhotoViewerScreen.kt:499-503`, `FileViewerScreen.kt:264`,
  `SodiumCrypto` `env["c"]!!/env["n"]!!`. Guarded today but brittle. **Fix:** `?: return`/`?: error(...)`.
- **[LOW] `IdleLocker.timeoutMs` public `@Volatile var`** — `IdleLocker.kt:10`. **Fix:** `private set`
  + validated setter.
- **[LOW] `BackoffInterceptor` interrupt handling** — `BackoffInterceptor.kt:19` restores the
  interrupt flag but doesn't `break`; the loop proceeds on an interrupted thread. **Fix:** `break`
  after re-interrupt.
- **[LOW] Accepted OSM tradeoffs (noted, not violations)** — osmdroid tile cache + tile requests to
  `tile.openstreetmap.org` and Nominatim forward-geocoding (`Geocoder.kt:28`) reveal approximate photo
  locations to OSM. Optional hardening: scope the osmdroid tile cache to an app-internal dir and clear
  it on lock/logout / in "Cache leeren".

---

## Verified clean (explicitly checked, no issue)

- **Crypto contract:** byte-accurate vs `vault.js` — Argon2id `ALG_ARGON2ID13` with **server-supplied**
  `kdf_ops`/`kdf_mem` (not hardcoded), 24-byte secretbox nonces from `SecureRandom`, keyless 32-byte
  generichash recovery, `{c,n}` base64 `NO_WRAP` (== libsodium ORIGINAL), u32le framing with
  HEADER/ABYTES, `sealManifest` 4-KiB **char-count** padding + Padmé — **matches the JS reference**
  (Kotlin `String.length` == JS `string.length`, both UTF-16 units; the "byte vs char" concern raised
  during review is a **false positive — do not change it** or you break parity).
- **Key lifecycle:** VK memory-only, `wipe()` overwrites on lock/idle/background/logout; KEK zeroed
  after use.
- **Token at rest:** AES-256-GCM StrongBox/TEE Keystore key, per-use `CryptoObject`-bound biometric/
  device-credential, `setInvalidatedByBiometricEnrollment(true)` — stronger than EncryptedSharedPreferences.
- **Transport:** `RESTRICTED_TLS` (TLS 1.2/1.3, strong ciphers) only, OkHttp SPKI `CertificatePinner`,
  cleartext blocked in `network_security_config`, RFC-compliant 429/`Retry-After` backoff with hostile-
  value cap, opaque Sanctum bearer (no JWT none-alg pitfall).
- **Privacy:** zero `Log`/`println`/`HttpLoggingInterceptor`/analytics/crash-reporting anywhere in
  `main`; the temporary `LLGAL` diagnostics are fully removed; no plaintext on disk (caches store only
  server ciphertext with a path-traversal guard; camera/PDF/share stream in-RAM; only user-consented SAF
  export writes plaintext); `FLAG_SECURE` in **both** `MainActivity` and `ShareActivity`;
  `allowBackup=false` + `dataExtractionRules` exclude root; exported components minimal + justified;
  deep-link validated; Padmé + 4-KiB padding on **every** upload; egress limited to the user's backend
  (+ the two accepted OSM services).
- **Android Lint:** `lintDebug` exit 0, no vital (release-blocking) errors.
- **Self-rolled code is justified:** `CodeHighlighter` (offline, no-Google, Compose-native),
  `Padme` (18 lines, spec-exact), `looksLikeText`, `Dates`/`Format` (java.time), `BackoffInterceptor`
  (429 semantics OkHttp lacks) — pulling libraries would add more fragility than it removes given the
  ZK / no-Google / minimal-dependency posture.

---

## Recommended order of remediation

1. **Quick wins (low risk, high value):** add wrapper `distributionSha256Sum`; pin biometric to
   stable `1.1.0`; move the `allowCleartext`/`forTest`/`Unused*` stubs out of `main`; fix the scattered
   `!!` force-unwraps + `UnlockVault` null-checks; `break` in `BackoffInterceptor`.
2. **Supply chain:** add the GitHub Actions CI (test+lint+assemble+OSV) and `verification-metadata.xml`;
   add a real release `signingConfig`.
3. **Maintainability:** inject `@ApplicationScope` and remove the ad-hoc singleton scopes; replace the
   startup `runBlocking`s; extract the `optimisticSave`/`deleteWithBackoff`/`cachedOr` DRY helpers;
   wire or delete `reconcile()`.
4. **Bigger upgrades (after CI):** batch dependency bumps; the AGP 9 / Kotlin 2.4 / KSP2 / Gradle 9
   toolchain move; Robolectric 4.16 (drops SDK-35 workarounds); plan osmdroid → MapLibre.
</content>
