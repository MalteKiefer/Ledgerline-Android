# Android Security Audit — Follow-up (ledgerline-android)

**Date:** 2026-07-13 · **Scope:** Android app only (no web/Laravel/CLI). Follow-up to
`2026-07-13-android-security-audit.md`, focused on code added since: the **remember-vault
biometric unlock** (persisted VK), keep-screen-on window flags, MapLibre map tiles,
remote-wipe. Standards: Kotlin Coding Conventions, Android Kotlin Style Guide, OWASP MASVS
/ Mobile Top 10, SOC 2 TSC. Scores: **CVSS v4.0** (vector = authoritative reproducible
artifact; numeric base is derived from the vector via the FIRST v4.0 rubric).

**Verdict:** Strong baseline holds (see PASS). No Critical/High. **5 Medium, 6 Low**, plus
maintainability items. The new VK-persistence feature is soundly designed (hardware key,
STRONG-biometric-only, per-use auth, enrollment-invalidation, TTL); findings are hardening,
not breaks. Severity was re-calibrated down from raw sub-scans (memory-hygiene ≠ Critical;
TTL tamper ≠ High because biometric+Keystore remain the real gate).

---

## Findings by severity (sorted by CVSS)

### MEDIUM

#### M1 — Argon2id KDF params accepted from server without bounds; KEK not wiped on wrong-passphrase
- **File:** `app/src/main/java/de/ledgerline/app/domain/usecase/UnlockVault.kt:30,37,39-40`
- **CVSS v4.0:** `AV:N/AC:H/AT:P/PR:N/UI:N/VC:H/VI:N/VA:L/SC:N/SI:N/SA:N` → **5.1 Medium**
- **MASVS:** MASVS-CRYPTO-2, MASVS-AUTH-1 · **SOC 2:** CC6.1 (Security)
- **Risk:** `kdf_ops`/`kdf_mem` come straight from `GET /vault` with no floor. A compromised
  or MITM'd server (pinning is TOFU) that also serves the wrapped VK can send `ops`≈0 /
  tiny `mem` → Argon2id becomes trivial → offline brute-force of the passphrase against the
  wrapped VK; a huge `mem` is a memory-exhaustion DoS. Separately, on the `WRONG_PASSPHRASE`
  path (line 39 early `return`) the derived `kek` is **not** zeroed (line 40 skipped).
- **Fix:** Reject implausibly weak params before deriving (e.g. `ops < 2` or
  `mem < 8 MiB` → error) and cap `mem` to a sane ceiling. Move `kek.fill(0)` into a
  `finally` so it is wiped on every path.

#### M2 — Decrypted Vault Key / session token left unzeroed in heap after unseal
- **Files:** `app/src/main/java/de/ledgerline/app/data/RememberedVaultStore.kt:105-109` ·
  `app/src/main/java/de/ledgerline/app/data/SessionStore.kt:46`
- **CVSS v4.0:** `AV:L/AC:H/AT:P/PR:H/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N` → **4.8 Medium**
- **MASVS:** MASVS-STORAGE-2 · **SOC 2:** C1.1 (Confidentiality)
- **Risk:** `String(sealer.finishOpen(...))` and `Base64.decode(vkB64)` create heap copies of
  the plaintext JSON (token + VK base64) and the VK bytes that are never wiped — they linger
  until GC. Requires privileged memory access (root/debugger/heap-dump), so not Critical, but
  it is a real **inconsistency with the app's own hygiene** (it zeroes the KEK, passphrase,
  and `VaultKeyHolder`, but not this copy of the crown-jewel VK).
- **Fix:** Decrypt into a `ByteArray`, parse, then `fill(0)` the plaintext bytes and the
  intermediate base64/VK arrays. Apply the same to `SessionStore.load`.

#### M3 — Map tiles leak viewed GPS coordinates + timing + IP to third-party OSM server
- **Files:** `app/src/main/java/de/ledgerline/app/ui/gallery/MapLibreSupport.kt:39` ·
  `LedgerlineApp.kt:37-46` · `ui/gallery/{GalleryMapScreen,OsmMap,LocationPickerScreen}.kt`
- **CVSS v4.0:** `AV:N/AC:L/AT:N/PR:N/UI:A/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N` → **5.1 Medium**
- **MASVS:** MASVS-PRIVACY-1 · **SOC 2:** Privacy (P) / C1.1
- **Risk:** Opening the gallery map or a geotagged photo makes MapLibre fetch `z/x/y` raster
  tiles from `tile.openstreetmap.org` derived from the user's **private photo coordinates**,
  with `User-Agent: de.ledgerline.app`. The tile server (and any network observer) learns
  the approximate locations, timing, and the app identity — egress to a third party, which
  conflicts with the ZK/no-third-party posture. (OSM tiles are an *accepted* third party per
  project constraints, so this is a documented trade-off, not a bug — but it is the single
  biggest privacy leak in the app.)
- **Fix (pick one):** proxy tiles through the user's own backend so only the backend sees
  coordinates; ship/cache offline tiles for viewed areas; coarsen zoom; or, at minimum,
  disclose in a privacy note + gate map rendering behind an explicit opt-in.

#### M4 — No Gradle dependency verification; vulnerable BouncyCastle 1.72 ships transitively
- **Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts` (pdfbox-android 2.0.27.0);
  missing `gradle/verification-metadata.xml`
- **CVSS v4.0:** `AV:N/AC:H/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:L/SI:L/SA:L` → **6.9 Medium**
  (band-limited to Medium given low likelihood)
- **MASVS:** MASVS-RESILIENCE / MASVS-CODE · **SOC 2:** CC8.1 (Change Mgmt)
- **Risk:** (a) No artifact checksum/PGP pinning — a compromised/typosquatted Maven artifact
  would be pulled silently despite HTTPS. (b) pdfbox-android 2.0.27.0 drags
  `org.bouncycastle:*-jdk15to18:1.72` into the APK, exposing **CVE-2023-33201** (LDAP
  injection), **CVE-2023-33202** (PEM-parser DoS), and the 2024 BC CVEs (fixed ≥1.78).
  Reachability is LOW (no `org.bouncycastle` references; PDFBox is render-only), but any
  scanner flags the shipped artifact. Note: **verification-metadata was already deferred as
  item #3** in the morning audit.
- **Fix:** `./gradlew --write-verification-metadata sha256 help`, review + commit. Force the
  transitive BC to ≥1.78 via a `constraints {}`/`resolutionStrategy.force` block, then verify
  PDF rendering still works.

#### M5 — `OfflinePrefs` blocks its constructor thread on 7× DataStore reads (`runBlocking`)
- **File:** `app/src/main/java/de/ledgerline/app/core/offline/OfflinePrefs.kt:57-75`
- **CVSS v4.0:** `AV:L/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:L/SC:N/SI:N/SA:N` → **2.1 Low-Medium**
- **MASVS:** MASVS-CODE · **SOC 2:** A1.2 (Availability), PI1 (Processing Integrity)
- **Risk:** A `@Singleton` whose property initializers each call `runBlocking { …first() }`
  synchronously blocks whatever thread first injects it (potentially main) on disk I/O →
  jank/ANR risk, the same class of bug just fixed in the gallery load path.
- **Fix:** Seed the cached values lazily/asynchronously (e.g. prime from the existing
  collector on an injected scope, or expose the values as `StateFlow`) instead of blocking in
  the constructor.

### LOW

#### L1 — Remember-vault TTL is plaintext + tamperable, and checked before the biometric (TOCTOU)
- **File:** `app/src/main/java/de/ledgerline/app/data/RememberedVaultStore.kt:54,87,101`
- **CVSS v4.0:** `AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:L/VA:N/SC:N/SI:N/SA:N` → **2.4 Low**
- **MASVS:** MASVS-STORAGE-2
- **Risk:** `remembered_expiry` is an unauthenticated `Long` in DataStore; a privileged local
  attacker can extend it, and the expiry is evaluated *before* the biometric prompt. This
  only weakens the *defense-in-depth* re-prompt policy — the actual VK unwrap still requires
  a STRONG biometric against a hardware Keystore key, which tampering cannot bypass.
- **Fix (optional hardening):** bind the expiry into the sealed blob (so tampering fails
  decryption), and re-check `now` after biometric success before returning the VK.

#### L2 — StrongBox silently falls back to TEE for the remembered-vault key
- **File:** `app/src/main/java/de/ledgerline/app/core/security/KeystoreSealer.kt:68-75`
- **CVSS v4.0:** `AV:P/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N` → **1.8 Low**
- **MASVS:** MASVS-CRYPTO-3
- **Risk:** On devices without a secure element the VK-sealing key downgrades from StrongBox
  to TEE with no signal. TEE + biometric is still acceptable, so this is informational, but
  the downgrade is invisible.
- **Fix:** Acceptable as-is; optionally log/telemetry-free note the tier, or offer a
  "require StrongBox" setting for high-assurance users.

#### L3 — Pairing deep-link `code` param not length/charset-validated
- **File:** `app/src/main/java/de/ledgerline/app/ui/scan/QrScanner.kt:45`
- **CVSS v4.0:** `AV:N/AC:L/AT:N/PR:N/UI:A/VC:N/VI:L/VA:L/SC:N/SI:N/SA:N` → **2.3 Low**
- **MASVS:** MASVS-PLATFORM-2
- **Risk:** `url` is https-validated but `code` is forwarded to the server unchecked (length,
  charset). One-time, server-authoritative, so impact is low; still worth a cheap guard.
- **Fix:** `if (code.isEmpty() || code.length > 512 || !code.matches(Regex("[A-Za-z0-9_-]+"))) return null`.

#### L4 — Share-target accepts `*/*` and reads external content URIs without pre-validation
- **Files:** `app/src/main/AndroidManifest.xml` (SEND/SEND_MULTIPLE `*/*`) ·
  `ui/share/ShareActivity.kt` (`contentResolver.getType/query` on incoming Uris)
- **CVSS v4.0:** `AV:L/AC:L/AT:N/PR:N/UI:A/VC:N/VI:L/VA:L/SC:N/SI:N/SA:N` → **2.4 Low**
- **MASVS:** MASVS-PLATFORM-1
- **Risk:** Any app can hand arbitrary content/URIs to the (necessarily) exported share
  target. Mitigated by the confirm-sheet and by importing only into the user's own vault;
  provider queries are largely `runCatching`-wrapped.
- **Fix:** Validate `uri.scheme in {content}` and wrap all ContentResolver access defensively;
  keep `*/*` (needed for the "any file" use case) but classify strictly.

#### L5 — Non-null assertions (`!!`) on nullable IO/HTTP results → crash/DoS
- **Files:** `data/{Gallery,Contact,File}BlobRepository.kt` (`res.body()!!.bytes()`),
  `ui/gallery/MapLibreSupport.kt` (`getDrawable(...)!!`), `ui/workspace/files/*` (`openInputStream(uri)!!`, `render!!`)
- **CVSS v4.0:** `AV:L/AC:L/AT:N/PR:N/UI:A/VC:N/VI:N/VA:L/SC:N/SI:N/SA:N` → **2.1 Low**
- **MASVS:** MASVS-CODE · **SOC 2:** A1.2
- **Fix:** Replace `!!` with `?: return Outcome.Err(...)` / graceful error state.

#### L6 — Biometric re-enrollment silently disarms remembered unlock
- **File:** `app/src/main/java/de/ledgerline/app/data/RememberedVaultStore.kt:111-114`
- **CVSS v4.0:** `AV:P/AC:L/AT:N/PR:H/UI:N/VC:N/VI:N/VA:N/SC:N/SI:N/SA:L` → **1.7 Low**
- **Risk:** Correct security behavior (dead blob is cleared), but the user gets no notice
  that quick-unlock was disabled — silent state change.
- **Fix:** One-time toast/note: "Biometrics changed — re-enable Unlock without passphrase."

---

## Maintainability / DRY (Info — no CVSS)

- **Duplicated biometric lambdas:** `authorize` + `strongAuthorize` are copy-pasted in
  `MainActivity.kt` and `ShareActivity.kt` → extract a shared factory/helper.
- **~95% identical blob repositories:** `Gallery/File/ContactBlobRepository` share cache→
  fetch→fallback→prefetch→upload→delete structure → extract `AbstractBlobRepository`.
- **Duplicated workspace `write()`** across Notes/Bookmarks/Todos/Contacts VMs → base class.
- **DataStore boilerplate** repeated in `SettingsStore`/`SessionStore`/`RememberedVaultStore`.
- **Large composables:** `GalleryScreen.kt` (~1170 LOC), `SettingsScreen.kt` (~940),
  `PhotoViewerScreen.kt` (~616) → split into sub-composables.
- Clean: no `TODO/FIXME/XXX`, no `LLGAL` remnants, no `GlobalScope`, domain layer Android-free.

---

## PASS — verified strong controls

- **Platform:** no WebView / exported ContentProvider / BroadcastReceiver / PendingIntent
  misuse. `FLAG_SECURE` on MainActivity + ShareActivity. `singleTask` + empty `taskAffinity`
  + `excludeFromRecents` on ShareActivity (task-hijack resistant). `allowBackup=false` +
  data-extraction-rules exclude everything.
- **Network:** cleartext disabled app-wide; `ConnectionSpec.RESTRICTED_TLS` (TLS 1.2+);
  SPKI SHA-256 pinning (TOFU); default hostname verification intact; no trust-all TrustManager.
- **Crypto/keys:** Argon2id via server params (kdf ok besides M1), SecretBox nonces from
  `SecureRandom`, VK in `@Volatile` wiped on lock, KEK/passphrase/recovery zeroed (besides
  M1 edge), token + VK sealed via hardware AES-256-GCM Keystore (per-use auth,
  invalidate-on-enrollment), remembered-VK key STRONG-biometric-only.
- **Privacy:** no `Log.*`/`println`/`printStackTrace` in app code; `Log.*` stripped in release
  via ProGuard `-assumenosideeffects`; disk caches store **ciphertext only**; no temp
  plaintext; no telemetry/Firebase/Google Play Services; only URL (non-secret) ever
  clipboarded; SettingsStore holds no secrets.
- **Build/supply-chain:** release signed via env/gitignored `keystore.properties` (no
  hardcoded creds); `isMinifyEnabled`+`isShrinkResources`+R8; Gradle wrapper
  `distributionSha256Sum` + `validateDistributionUrl`; no dynamic versions; HTTPS-only repos,
  no `mavenLocal()`; no secrets/keystores committed. Toolchain fully current (AGP 9.2.0,
  Kotlin 2.4.0, KSP 2.3.10, Gradle 9.6.1, Compose BOM 2026.06.01, Hilt 2.60.1).
- **CI:** least-privilege `permissions: contents: read`; `pull_request` (not
  `pull_request_target`); Gradle wrapper-validation; unit tests + lint + OSV scan; no secret
  echo.

---

## Corrections to raw sub-scans

- `autoVerify="false"` on `ledgerline://pair` is **not a finding** — App-Links auto-verify
  applies only to `http`/`https`; for a custom scheme it is a no-op. (The pairing flow is
  server-gated + https-validated + one-time-code, so custom-scheme openness is acceptable.)
- "VK not zeroed" is **Medium, not Critical** — process-private memory; needs root/heap-dump.
- "TTL tampering" is **Low, not High** — biometric + hardware Keystore remain the real gate;
  tampering the plaintext expiry cannot unwrap the VK.

---

## SOC 2 note

The app deliberately keeps **no security audit log** (ZK/privacy model). For a SOC 2 CC7.x
(monitoring) assessment this is a gap-by-design at the client; security-event logging, if
required, belongs on the server side, not in this zero-knowledge client.

---

## Remediation status (implemented 2026-07-13)

| Finding | Status | What changed |
|---|---|---|
| M1 | ✅ Fixed | KDF cost bounds (ops 2–100, mem 8 MiB–2 GiB) + `finally`-wipe KEK on every path. `UnlockVault.kt` + new test `weak_kdf_params_are_rejected`. |
| M2 | ✅ Fixed | Zero decrypted plaintext bytes after parse in `RememberedVaultStore.open` + `SessionStore.load` (transient JSON String residual documented). |
| M3 | ✅ Fixed | New `mapTilesEnabled` setting (default OFF) + `MapTilesGate`; passive map surfaces (`OsmMap`, `GalleryMapScreen`) don't fetch OSM tiles until opt-in. LocationPicker left ungated (intentional map use). |
| M4 | ⚠️ Partial | BouncyCastle forced to 1.78.1 (CVEs cleared, verified `1.72 -> 1.78.1`). **`verification-metadata.xml` still pending** — needs a dedicated pass covering all configs + clean-build validation. |
| M5 | ✅ Fixed | `OfflinePrefs` no longer `runBlocking` in its ctor; seeds store-default values, collectors update. |
| L1 | ✅ Fixed | Post-biometric TTL re-check in `RememberedVaultStore.open` (closes TOCTOU). Plaintext-expiry-tamper accepted as soft control by design. |
| L2 | ⏸ Accepted | StrongBox→TEE fallback kept (documented; TEE + biometric acceptable). |
| L3 | ✅ Fixed | Pair `code` length/charset guard in `QrScanner.parsePairLink`. |
| L4 | ✅ Fixed | Share import restricted to `content://` URIs + defensive `runCatching` in `ShareActivity.parseIntent`. |
| L5 | ✅ Fixed | `getDrawable`/`openInputStream` `!!` replaced with graceful fallbacks; dead `drawableToBitmap` removed. `res.body()!!` (in try/catch → Err) left as codebase idiom. |
| L6 | ⏸ Deferred | Re-enrollment user-notice not added (minor UX). |
| DRY | ✅ Partial | Biometric authorizer lambdas extracted to `VaultAuthorizers` (MainActivity + ShareActivity). `AbstractBlobRepository` extraction deferred (larger refactor). |

Build green, 352 unit tests pass, APK installed.

## Recommended fix order

1. **M1** — bound KDF params + `finally`-wipe KEK (cheap, real crypto hardening).
2. **M2** — zero decrypted VK/token copies in RememberedVaultStore + SessionStore.
3. **M4** — `verification-metadata.xml` (already planned) + force BouncyCastle ≥1.78.
4. **M3** — decide the map-tile privacy stance (proxy / offline / disclose).
5. **M5** — de-block `OfflinePrefs` init.
6. **L1–L6** — cheap guards + wiping + a re-enrollment notice.
7. **DRY** — extract biometric-lambda helper + `AbstractBlobRepository` when convenient.
