# Phase 4d — Duplicate scan + background execution

**Goal:** Add a client-side duplicate-photo scan (pHash + CLIP), and let long
operations (face scan, duplicate scan, uploads; later bulk blob delete/reconcile)
run in the background via a foreground service, with the auto-lock wipe deferred
until the operation finishes — gated by a user setting.

**Ground truth:** `resources/js/app.js` — `scanDuplicates` / `_dupGroupsInline`
(~L1450–1546), `_dot`/`_norm` (~L1280–1291). Existing lock lifecycle in
`MainActivity.kt`, `IdleLocker`, `LockGuard`, `VaultKeyHolder`.

Security frame: the ZK model wipes VK + all decrypted caches on background/idle.
Running an op in the background necessarily keeps the VK in memory for the op's
duration — a deliberate, user-consented relaxation, scoped to *active operations
only*, behind a visible foreground-service notification, and off by default-able via
Settings. After the op ends (or if the setting is off), the normal lock wipe applies.

---

## D1 — Foundation (no behaviour change)

### `core/security/VaultLocker.kt` (`@Singleton`)
Extract the two identical inline wipes in `MainActivity` into one primitive:
```kotlin
@Singleton
class VaultLocker @Inject constructor(
    private val vaultKeyHolder: VaultKeyHolder,
    private val sessionHolder: SessionHolder,
    private val workspaceCache: WorkspaceCache,
    private val galleryCache: GalleryCache,
    private val thumbCache: ThumbCache,
    private val metaCache: MetaCache,
) {
    /** Lock the vault: wipe VK + all in-memory decrypted state. Does NOT touch the
     *  persisted session or keystore key (that is logout, see ForceLogout). */
    fun lock() {
        vaultKeyHolder.wipe(); sessionHolder.clear(); workspaceCache.clear()
        galleryCache.clear(); thumbCache.clear(); metaCache.clear()
    }
}
```
Refactor `MainActivity`'s `onStop` and `onResume` wipes to call `locker.lock()`
(inject `VaultLocker`, drop the individual cache fields it no longer needs directly —
keep what other code paths still use). Behaviour identical.

### `PhotoMetaBlob.phash`
Add `val phash: Long? = null` to `domain/model/PhotoMetaBlob` (the server sends a
signed 64-bit number; kotlinx decodes a JSON number to `Long`). Tolerant parsing
already ignores it when absent.

## D2 — Background operation infrastructure

### `core/ops/OperationManager.kt` (`@Singleton`)
Owns an application-scoped coroutine scope so work survives the Activity going to
background. Tracks active operations + progress; drives the foreground service and
the deferred-wipe decision.
```kotlin
enum class OpKind { FACE_SCAN, DUPLICATE_SCAN, UPLOAD, BLOB_CLEANUP }
data class OpProgress(val id: Long, val kind: OpKind, val current: Int, val total: Int)

@Singleton
class OperationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val locker: VaultLocker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val active: StateFlow<List<OpProgress>>   // for UI overlays + service notification
    @Volatile private var appInBackground = false

    /** Run [block] as a tracked operation. [block] receives a progress reporter.
     *  Returns the Job so callers can cancel. Runs in the app scope (survives
     *  background). Starts/stops the foreground service around the active window. */
    fun run(kind: OpKind, total: Int = 0, block: suspend (report: (Int, Int) -> Unit) -> Unit): Job

    fun hasActive(): Boolean
    /** Called by MainActivity lifecycle. */
    fun onAppBackground()  // set flag; if !backgroundEnabled, nothing special
    fun onAppForeground()
}
```
Behaviour:
- `run` allocates an id, adds an `OpProgress` to `active`, launches in `scope`,
  removes it on completion/cancel/error. The `report(current,total)` updates that
  entry in `active`.
- When `active` becomes non-empty AND the background setting is enabled → start
  `BackgroundOpService` (foreground). When it becomes empty → stop the service, and
  **if the app is currently backgrounded, call `locker.lock()`** (deferred wipe now
  that the op is done). If the setting is disabled, never start the service and do
  not defer — the op still runs but MainActivity's normal wipe on background will
  cancel the app scope (see D2 wiring).
- Reads `settings.backgroundOpsEnabled` (a `StateFlow`/cached value; read latest).

### `core/ops/BackgroundOpService.kt` (foreground `Service`, type `dataSync`)
- `startForeground` with a low-priority ongoing notification (channel
  `ledgerline_ops`) summarising `active` (e.g. "Verarbeitung läuft… Gesichter 12/40").
  Observe `OperationManager.active` (inject via Hilt `@AndroidEntryPoint` service or
  an entry-point) and update the notification text; `stopSelf` when empty.
- No content beyond counts — never leak names/paths.

### Manifest + permissions
Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
...
<service android:name=".core.ops.BackgroundOpService"
         android:foregroundServiceType="dataSync" android:exported="false" />
```
Request `POST_NOTIFICATIONS` at runtime (Android 13+) the first time a background op
is started with the setting on (or when the setting is toggled on) — a simple
`rememberLauncherForActivityResult` in Settings, or on first op. If denied, the op
still runs; the service shows no visible notification (Android suppresses it) but
keeps the process alive — acceptable.

### Settings toggle
`SettingsStore`: add
```kotlin
private val bgOpsKey = booleanPreferencesKey("background_ops_enabled")
val backgroundOpsEnabled: Flow<Boolean> = data.map { it[bgOpsKey] ?: true }  // default ON
suspend fun setBackgroundOpsEnabled(v: Boolean)
```
Add a switch row in `SettingsScreen` ("Hintergrund-Verarbeitung" / "Background
processing", subtitle explaining the tradeoff + that a notification shows while it
runs). When toggled on, trigger the POST_NOTIFICATIONS request.

### MainActivity wiring
- Inject `OperationManager` + `VaultLocker` + `SettingsStore`.
- `onStop`: if `lockGuard.consumeSkip()` → skip (as today). Else if
  `bgEnabled && operationManager.hasActive()` → **defer**: call
  `operationManager.onAppBackground()` and do NOT wipe (the manager wipes when the op
  finishes). Else → `locker.lock()` (immediate, as today).
- `onResume`: `operationManager.onAppForeground()`. Then the existing idle-expiry
  check → `locker.lock()` — BUT skip the idle wipe while `hasActive()` (deferred
  choice: idle does not abort a running op). Keep `lockGuard.clear()`.
- Read `bgEnabled` from settings (cache latest via a collected flow or `runBlocking`
  first + keep updated).

## D3 — DuplicateScanner (pure Kotlin, unit-tested)

`domain/gallery/DuplicateScanner.kt` — exact port of `_dupGroupsInline`:
```kotlin
data class DupItem(val id: String, val embNorm: List<Double>?, val phash: Long?, val isVideo: Boolean)
object DuplicateScanner {
    fun dot(a: List<Double>, b: List<Double>): Double            // sum a[i]*b[i], i in 0 until min
    fun norm(v: List<Double>): List<Double>                       // L2-normalise (zero vec → zeros)
    fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()
    /** Union-find groups of duplicates; returns groups (size>1) of item ids,
     *  each group sorted by the caller. Progress via [report]. */
    suspend fun groups(items: List<DupItem>, report: (Int, Int) -> Unit): List<List<String>>
}
```
Rules (byte-for-byte):
- `embNorm` = normalised CLIP vector or null; `phash` null → treat pairwise Hamming
  as 64 (no match by phash).
- For pair (i,j): `hd = if (phash_i!=null && phash_j!=null) hamming else 64`;
  `dup = if (isVideo_i || isVideo_j) hd <= 4 else ((embNorm_i!=null && embNorm_j!=null && dot >= 0.97) || hd <= 3)`.
- Union-find (path-halving), collect roots, keep groups length>1. O(n²); `report`
  every ~16 outer iterations.

The caller (ViewModel) sorts each group by photo `size` descending (largest first),
mapping ids back to `GalleryPhoto`.

## D4 — Duplicate scan UI + ViewModel

`ui/gallery/DuplicatesViewModel.kt` (`@HiltViewModel`): inject `GalleryCache`,
`MetaCache`, `GalleryBlobs`, `MutateGallery`, `OperationManager`.
- `scan()` via `OperationManager.run(OpKind.DUPLICATE_SCAN)`: ensure meta for
  non-trashed photos (download+decrypt metaRef → `PhotoMetaBlob`, cache), build
  `DupItem`s (`embNorm = norm(meta.embedding)` when non-empty else null; `phash`;
  `isVideo = media_type=="video"`), run `DuplicateScanner.groups`, map to
  `List<List<GalleryPhoto>>` sorted by size desc. Publish `groups: StateFlow`.
- Mark state: `marked: SnapshotStateList/StateFlow<Set<String>>`; `toggleMark(id)`,
  `markRest(group)` (mark all but the first/largest), `trashMarked(group)` → set
  `trashed=true` on marked photos via `MutateGallery`, drop them from the group,
  clear marks, drop groups that fall to size≤1.
- Progress read from `OperationManager.active`.

`ui/gallery/DuplicatesScreen.kt`: full-screen (entered from a Gallery overflow
action "Duplikate suchen"). Lists each group as a row/grid of thumbs (reuse
`ThumbCell`/selectable cell), tap toggles a delete mark, per-group "Rest markieren"
button + a global "Markierte löschen" action. Progress overlay from
`OperationManager.active`. Empty result → "Keine Duplikate".

Entry point: add to `GalleryScreen` an overflow/top action (a small menu on the
Photos tab, or a menu item in the People/scan area) → sets a `showDuplicates`
full-screen state (mirror the camera/detail early-return pattern).

## D5 — Route existing ops through OperationManager

- Face scan (`PeopleViewModel.scanFaces`) → wrap the body in
  `OperationManager.run(OpKind.FACE_SCAN)`, report progress through the reporter;
  keep the People screen reading progress from `OperationManager.active` (or keep the
  VM's own StateFlow mirroring it — pick one; prefer reading `active`).
- Uploads (`GalleryViewModel.uploadAll`) → `OperationManager.run(OpKind.UPLOAD, total=sources.size)`,
  report per item.
- Unify the progress overlay: a small composable reads `OperationManager.active` and
  renders the current op(s) with counts; reuse it in Gallery/People/Duplicates.
- Bulk blob delete/reconcile: not yet implemented (only soft-trash exists). Leave a
  `OpKind.BLOB_CLEANUP` slot; wire when that feature lands.

## Testing

- `DuplicateScannerTest` (JVM): dot/norm/hamming; two near-identical embeddings
  (dot≥0.97) group; two images with Hamming≤3 group; video pair needs Hamming≤4;
  phash-null pair only groups via embedding; three-way transitive union; lone item
  excluded; group>1 filter.
- `VaultLockerTest` (JVM): `lock()` clears the in-memory holders/caches (fakes for
  the two Android-touching ones as in ForceLogoutImplTest).
- `OperationManager`: a focused test that `run` adds/removes from `active`, reports
  progress, and that `hasActive()` reflects state (use a `TestScope`/`runTest`;
  inject a fake `SettingsStore`/`VaultLocker`; the service start/stop can be behind a
  small injectable `ServiceController` interface so the JVM test can assert
  start/stop without Android).
- On-device smoke: toggle background on; start a face scan; press Home; confirm the
  notification shows and the scan finishes (people appear on return); confirm that
  with the toggle OFF, backgrounding cancels/locks. Duplicate scan finds groups and
  trashing removes them. No plaintext in logcat.

## Security checklist (this phase)

- VK retained in memory only while an op runs AND only with the setting on; wiped by
  `VaultLocker.lock()` when the op ends in background, or immediately on background
  when no op / setting off. Idle wipe deferred during an active op (user choice).
- Foreground-service notification shows counts only — never names/paths/content.
- MetaCache (plaintext embeddings) still wiped by `VaultLocker.lock()`.
- No new network endpoints. Duplicate scan is fully on-device.
- POST_NOTIFICATIONS is the only new user-facing permission; all AOSP, no Google.
