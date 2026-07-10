# Phase 5c — Prefetch + cache limits

**Goal:** Proactively pre-download referenced blob **ciphertext** into the offline
cache per a user-chosen policy, bound the cache with a size limit + LRU eviction, and
add the settings to control it. Builds on 5a's `BlobDiskCache`/`StoreDiskCache`.

**Runtime decision (important):** prefetch does NOT use WorkManager. It needs the
in-memory session token (Keystore-only, wiped on lock) and the blob-ref list from the
*decrypted* manifest (VK-gated). Persisting either to a WorkManager job = writing
secrets to disk, which violates the token/VK model. So prefetch runs **in-app via the
Phase-4d `OperationManager`** (`OpKind.PREFETCH`) while unlocked — the foreground
service keeps it alive across backgrounding (when background ops are enabled), and the
ref list is snapshotted at start. Downloads fetch ciphertext only (no VK needed), so a
lock mid-run doesn't corrupt anything; a wiped session just ends the run.

**Trigger:** manual ("Prefetch now") **and** automatically on unlock when the policy is
a prefetch policy (ALL/THUMBS) and the Wi-Fi/charging constraints are met.

---

## C1 — Settings policy model (replace 5a booleans with enums)

`data/offline/OfflinePolicy.kt`:
```kotlin
enum class FileBlobPolicy { OFF, ON_DEMAND, ALL }
enum class PhotoBlobPolicy { OFF, THUMBS, ON_DEMAND, ALL }
```
`SettingsStore`: replace `offline_files_blobs`/`offline_photos_blobs` booleans with:
- `offline_files_policy: String` (enum name, default `ON_DEMAND`) — migrate old `true`→`ON_DEMAND`, absent/`false`→`OFF`.
- `offline_photos_policy: String` (default `ON_DEMAND`).
- `offline_cache_max_mb: Int` (default `1024`; `0` = unlimited). Options {512, 1024, 2048, 0}.
- `prefetch_wifi_only: Boolean` (default **true**).
- `prefetch_charging_only: Boolean` (default **true**).
Keep the master `offline_cache_enabled` (default true). Add `Flow` getters + setters
for all. Update `OfflinePrefs`/`OfflineFlags` synchronous accessors:
```kotlin
interface OfflineFlags {
    fun enabled(): Boolean
    fun filesPolicy(): FileBlobPolicy
    fun photosPolicy(): PhotoBlobPolicy
    fun maxBytes(): Long          // 0 = unlimited
    fun wifiOnly(): Boolean
    fun chargingOnly(): Boolean
}
```
Repos' cache-on-access gating (from 5a) becomes: files cache when
`enabled() && filesPolicy() != OFF`; gallery cache when
`enabled() && photosPolicy() != OFF`. (THUMBS still caches on-access whatever is
viewed — the THUMBS/ALL distinction only shapes *prefetch* scope, below.)

## C2 — Cache size limit + LRU eviction (`BlobDiskCache`)

- On `get(id)` hit, touch the file (`setLastModified(nowMillis)`) so recency reflects
  reads, not just writes. (Use `System.currentTimeMillis()` — this is not crypto.)
- Add `fun enforceLimit(maxBytes: Long)`: if `maxBytes <= 0` no-op; else while
  `sizeBytes() > maxBytes`, delete the file with the oldest `lastModified` (LRU).
- `put(id, bytes)`: after writing, call `enforceLimit(offlineFlags.maxBytes())` — inject
  `OfflineFlags` into `BlobDiskCache` (it becomes non-trivial; keep the `File root`
  primary ctor for tests and add the flags dep, defaulting to an "unlimited" fake in
  tests). Never evict the blob just written.
- Tests: eviction removes oldest first; unlimited (0) never evicts; touch-on-get
  protects a recently read blob from eviction.

## C3 — Prefetch engine

### Repo prefetch (ciphertext only, no VK)
Add to `GalleryBlobRepository` + `FileBlobRepository`:
```kotlin
/** Fetch a blob's ciphertext and store it in the disk cache. No decryption, no VK.
 *  Skips if already cached. Returns true on cache hit-or-stored, false on failure. */
suspend fun prefetch(ref: String): Boolean
```
Gallery uses `galleryRaw(ref)`, files use `rawFile(ref)`; on 2xx → `blobCache.put(ref, bytes)`; skip when `blobCache.has(ref)`. Needs only the session (token) — no `vaultKeyHolder`.

### `data/offline/ConstraintChecker.kt` (`@Singleton`)
AOSP only. `fun wifiConstraintMet(wifiOnly): Boolean` (true if `!wifiOnly` or the active
network is unmetered — extend `Connectivity` with `isUnmetered(): Boolean` via
`NetworkCapabilities.NET_CAPABILITY_NOT_METERED`). `fun chargingConstraintMet(chargingOnly): Boolean`
(true if `!chargingOnly` or `BatteryManager`/`ACTION_BATTERY_STATUS` reports charging/full).

### `core/offline/Prefetcher.kt` (`@Singleton`)
Inject `GalleryCache`, `WorkspaceCache`, `GalleryBlobRepository`, `FileBlobRepository`,
`BlobDiskCache`, `OfflineFlags`, `ConstraintChecker`, `OperationManager`.
```kotlin
fun prefetchNow()   // manual: run regardless of auto rules, but still honour constraints
fun maybePrefetchOnUnlock()  // auto: only if a prefetch policy is active
```
Logic (both route to a private `run(force)`):
- If `!offlineFlags.enabled()`, or both policies are non-prefetch (files != ALL and
  photos !in {THUMBS, ALL}), do nothing (for the auto path). Manual runs whenever at
  least one policy caches anything (ALL/THUMBS); if everything is OFF, no-op.
- Check constraints: if `!constraintChecker.wifiConstraintMet(offlineFlags.wifiOnly())`
  or `!chargingConstraintMet(offlineFlags.chargingOnly())` → skip (manual: surface a
  message "constraints not met"; auto: silent).
- Snapshot refs from the in-memory decrypted caches (VK already available while
  unlocked):
  - Photos (`GalleryCache.value.manifest.photos`, non-trashed): THUMBS →
    `thumbRef`; ALL → `thumbRef,mediumRef,originalRef,motionRef,metaRef,*faceCropRefs`.
    (Skip nulls.)
  - Files (`WorkspaceCache.value.manifest.files`, non-trashed): ALL → `blob`.
- Dedup refs, drop those already `blobCache.has(ref)`.
- Run through `OperationManager.run(OpKind.PREFETCH, total = refs.size) { report -> ... }`:
  for each ref call the right repo's `prefetch(ref)`; `report(done, total)`; between
  items re-check the size limit is handled by `put`'s `enforceLimit`. Stop early if the
  cache is at the limit AND the limit is finite and further puts would just evict
  (optional: keep it simple — let `enforceLimit` manage it, but note that ALL + a small
  limit will thrash; that's the user's setting).
- Add `OpKind.PREFETCH` to `OperationManager`.

## C4 — Settings UI + auto-trigger

### Settings "Offline" section (extend the 5a section)
Replace the two blob switches with:
- Files policy selector (`settings_files_policy` → dialog/segmented: Off / On demand / All).
- Photos policy selector (`settings_photos_policy` → Off / Thumbnails / On demand / All).
- Cache size limit selector (`settings_cache_limit` → 512 MB / 1 GB / 2 GB / Unlimited).
- Wi-Fi-only switch (`settings_prefetch_wifi`, default on).
- Charging-only switch (`settings_prefetch_charging`, default on).
- "Prefetch now" button (`settings_prefetch_now`) → `prefetcher.prefetchNow()`; show the
  shared `OpProgressOverlay` (PREFETCH appears there automatically) / a snackbar if
  constraints unmet.
Keep the existing cache-size line + "Clear cache". Selectors use the existing dropdown/
dialog pattern (mirror the idle-timeout selector). Strings in both `values/` + `values-de/`.

### Auto-prefetch on unlock
Observe unlock. Simplest: in `RootViewModel` (or wherever unlock lands / `WorkspaceScaffold`'s
`LaunchedEffect`), call `prefetcher.maybePrefetchOnUnlock()` once after the manifests are
loaded. Inject `Prefetcher`. It self-gates on policy + constraints, so calling it
unconditionally on unlock is safe. Ensure it runs after `GalleryCache`/`WorkspaceCache`
are populated (the caches drive ref enumeration) — trigger it after a successful
workspace+gallery load, or defer inside `Prefetcher` until caches are non-null (skip if
null, it'll fire again next load). Keep it debounced (don't stack runs — `OperationManager`
already tracks active PREFETCH; skip if one is active).

## Tests
- `OfflinePolicy` migration in `SettingsStore` (old bool → enum) if feasible in JVM.
- `BlobDiskCache` LRU eviction (C2) — the core new logic.
- `Prefetcher` ref enumeration per policy (THUMBS vs ALL vs OFF) + constraint gating +
  dedup-vs-already-cached, using fake caches/repos/OperationManager (mockk relaxed) and a
  fake `ConstraintChecker`. Assert the right refs are handed to the repos and constraint
  failure short-circuits.
- Repo `prefetch(ref)`: online 2xx caches ciphertext; already-cached is skipped; failure → false.

## Verification
- `testDebugUnitTest` + `assembleDebug` + `assembleRelease`.
- On-device: set Photos=All, Wi-Fi-only on; on Wi-Fi tap "Prefetch now" → progress runs,
  cache size grows; airplane mode → gallery loads from cache. Set a 512 MB limit, prefetch
  a large library → size stays under limit (LRU). Unlock with policy=All on Wi-Fi+charging
  → auto-prefetch kicks off. No plaintext on disk.

## Security checklist
- Prefetch stores ciphertext only; no VK used for downloads. Session token stays in
  memory (never persisted to a job). Enumeration reads the in-memory decrypted manifest
  while unlocked. Cache cleared on disconnect/401 (from 5a) unchanged.
