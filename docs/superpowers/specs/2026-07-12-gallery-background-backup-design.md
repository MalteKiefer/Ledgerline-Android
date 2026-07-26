# Gallery Background Backup — Design

**Status:** approved (brainstorm) — 2026-07-12
**Feature:** Immich-style camera-roll auto-backup for the ledgerline-android
zero-knowledge client (CLAUDE.md §13).

---

## 0. Goal

Automatically back up the user's device photos/videos into the encrypted gallery,
reusing the existing upload pipeline, **without weakening the zero-knowledge model**.

## 1. The zero-knowledge constraint (shapes everything)

- Encrypting a photo needs the **Vault Key (VK)** — in-memory only, wiped on
  lock/idle.
- Uploading needs the **Sanctum token** — in memory after unlock, but sealed at rest
  behind a biometric-gated Keystore key (`SessionStore.load` requires a biometric).

Therefore a truly **app-closed / locked** `WorkManager` job can neither encrypt nor
authenticate. True Immich-style "always-on background upload" is **impossible** here
without persisting a non-biometric key — which the security model forbids.

**Chosen model: unlocked-only foreground-service backup.** A backup run executes only
while the vault is unlocked. It runs through the existing `OperationManager`
(foreground service + notification), so it **survives the app being backgrounded** as
long as the process stays alive and unlocked. A running op **defers the idle auto-lock
wipe** (already how background ops behave), keeping the VK alive for the run's duration.
When the run ends, the normal idle lock resumes. A killed/locked process just stops the
run; the next unlock resumes.

## 2. Reuse — most of the pipeline already exists

`domain/usecase/ImportPhotos` is the pure import loop already shared by the Gallery
screen (`GalleryViewModel.uploadAll`) and the share target (`ShareViewModel`). It:
reads bytes, computes the **SHA-256 `sig`**, **dedups against the known sigs** in the
decrypted gallery index, encrypts + Padmé-pads + uploads the original, runs the
`/gallery/process` pipeline (renditions + meta: EXIF/GPS/embedding/pHash/faces),
appends the photo entry, and returns `ImportResult(done, failed)`. It deliberately does
**not** wrap itself in `OperationManager` — the caller owns the op and passes a
`report(done, total)`.

```kotlin
data class PhotoSource(val name: String, val mime: String, val read: () -> ByteArray,
                       val lat: Double? = null, val lng: Double? = null)
interface ImportPhotos {
    suspend fun invoke(sources: List<PhotoSource>, report: (Int, Int) -> Unit): ImportResult
}
```

So the backup feature does **not** re-implement encryption, upload, `/gallery/process`,
dedup, or the store write. It only: discovers device media, turns each new item into a
`PhotoSource`, wraps the batch in an operation, and remembers what's done.

## 3. Architecture & new components

Small, single-purpose, JVM-testable units:

- **`DeviceAlbums`** (`data/backup/DeviceAlbums.kt`) — queries `MediaStore` for image +
  video **buckets** (bucket id, display name, item count, a sample content-uri for a
  thumbnail). Feeds the album-selection UI. Injectable `ContentResolver` seam.

- **`BackupScanner`** (`data/backup/BackupScanner.kt`) — given the selected bucket ids,
  queries `MediaStore` and returns candidate items
  `BackupItem(mediaStoreId: Long, uri: Uri, name: String, mime: String, sizeBytes: Long,
  dateTakenMs: Long)`, newest first. Injectable `ContentResolver` seam.

- **`BackupStateStore`** (`data/backup/BackupStateStore.kt`) — a device-local record of
  already-backed-up `MediaStore` ids (DataStore). Purely a **fast-skip optimization** so
  a run doesn't re-read + re-hash every device photo each time. Stores only ids (device-
  local, non-sensitive) — the authoritative dedup remains the `sig` check inside
  `ImportPhotos`.

- **`GalleryBackupManager`** (`core/backup/GalleryBackupManager.kt`, `@Singleton`) —
  orchestrates a run:
  1. If master switch off, or no selected albums, or `sessionHolder.get() == null`, or
     `vaultKeyHolder.get() == null` (locked) → no-op.
  2. Check constraints via the existing `ConstraintChecker` (Wi-Fi-only / charging-only,
     reusing the offline prefetch settings). Not met → skip, surface reason.
  3. `BackupScanner` lists candidates for the selected buckets.
  4. Drop candidates whose `MediaStore` id is already in `BackupStateStore`.
  5. Build a `PhotoSource` per remaining item
     (`read = { resolver.openInputStream(uri)!!.readBytes() }`, lat/lng null → server
     reads EXIF).
  6. `operationManager.run(OpKind.BACKUP, total = sources.size) { report ->
     importPhotos.invoke(sources, report) }`.
  7. On completion mark the uploaded/deduped ids in `BackupStateStore`.
  - Injectable dispatcher seam for deterministic tests (mirrors `OperationManager` /
    `GalleryViewModel`).

- **`BackupSettings`** — new keys in `SettingsStore`: `backupEnabled: Boolean`,
  `backupAlbumIds: Set<String>`, plus reuse of the existing Wi-Fi-only / charging-only
  prefetch constraint prefs. (No new constraint prefs — shared with prefetch.)

- **`OpKind.BACKUP`** — new value in the `OpKind` enum + a label string
  (`ops_kind_backup`) in `BackgroundOpService`.

## 4. Triggers

- **Auto on unlock:** the unlock success handler (the site that calls
  `vaultKeyHolder.set(...)` — the same place `Prefetcher.maybePrefetchOnUnlock()` is
  triggered) also calls `galleryBackupManager.maybeRun()`. `maybeRun()` self-gates on all
  conditions in §3.1–3.2.
- **Manual "Back up now":** a button in the backup settings screen → `maybeRun(force =
  true)` (still constraint-checked; `force` only bypasses the "auto" silence, surfacing
  a reason if constraints block, exactly like `Prefetcher.prefetchNow`).
- **No `WorkManager`.** Runs are while-alive only, consistent with the ZK limit (see
  the existing `BackgroundSync` decision).

## 5. Dedup & resumability

- **Authoritative dedup:** the `sig` (SHA-256 of original bytes) vs the gallery index,
  done inside `ImportPhotos` — no double uploads, even across devices.
- **Fast skip:** `BackupStateStore` (MediaStore id set) avoids re-hashing on every run.
- **Resumability:** per-photo atomicity is already `ImportPhotos`' contract (all blobs
  uploaded, then one store `PUT`, then counted). An abort mid-item leaves that item
  un-backed-up; the next run retries it. Orphaned blobs from a partial item are swept by
  the existing `reconcile`. `BackupStateStore` is marked only for items `ImportPhotos`
  reports as done/deduped.

## 6. Permissions

- `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (SDK 36 granular media perms; full-album
  access, not the partial "selected photos" grant).
- `POST_NOTIFICATIONS` (foreground-service notification) — already handled for
  background ops.
- Foreground-service type `dataSync` — already declared.
- Offered on the **welcome screen** (mirroring the location/contacts cards) **and** gated
  in the backup settings screen (request lazily when the master switch is turned on).

## 7. Settings & status UI

New settings sub-screen **"Foto-Backup"** — a dedicated `SettingsRoute.BACKUP` entry in
the settings root list (its own screen, not folded into the background category):

- Master toggle "Camera backup".
- **Album multi-select** — `DeviceAlbums` list: name + count + thumbnail + checkbox.
- Wi-Fi-only / charging-only toggles (reuse the prefetch constraint prefs).
- "Back up now" button.
- Status line: "last backup: <time> · N pending · M backed up · K failed".

Progress of a running `OpKind.BACKUP` shows through the existing `OpProgressOverlay` +
foreground-service notification (X / N). No new progress UI.

## 8. Error handling

- Per-photo failure never aborts the run — `ImportPhotos` already counts `failed` and
  continues; the next run retries (those ids are not marked done).
- `429` → existing upload backoff. Store `PUT` `409` → existing merge/retry.
- Constraint lost mid-run (Wi-Fi drops) — out of scope to interrupt an in-flight run;
  the constraint is checked at run start. (A mid-run cancel is a later refinement.)
- Permission revoked / quota full (`/gallery/usage`) → the run fails fast; the failure
  count + a status message surface it.

## 9. Scope — v1 deliberately does NOT

- No true app-closed backup (ZK limit).
- No Live/Motion photos (stays in the gallery-editing backlog).
- No "original vs scaled" option — v1 always uploads the full-res original.
- No "only since date X" — v1 considers all items in the selected albums; the `sig`
  dedup prevents re-uploading anything already in the gallery.
- Videos in selected albums are backed up via the same `/gallery/process` pipeline.
- Faces / places / embeddings come from the server's `/gallery/process` — no extra work.

## 10. Testing (JVM + fakes, no device)

- `BackupScanner` with a fake `ContentResolver` → returns expected candidates for given
  buckets.
- `DeviceAlbums` with a fake `ContentResolver` → bucket list with counts.
- `BackupStateStore` round-trip (mark / contains / persist).
- `GalleryBackupManager` run with a fake `ImportPhotos` + fake state + fake constraints:
  - only un-marked candidates become `PhotoSource`s,
  - `ImportPhotos` is invoked with them and progress reported,
  - reported-done ids get marked in `BackupStateStore`,
  - locked / disabled / no-albums / constraint-blocked → no-op,
  - dispatcher seam drives deterministic completion.

## 11. File structure

```
core/backup/GalleryBackupManager.kt      (new)
data/backup/DeviceAlbums.kt              (new)
data/backup/BackupScanner.kt             (new)
data/backup/BackupStateStore.kt          (new)
data/SettingsStore.kt                    (backupEnabled, backupAlbumIds)
core/ops/OperationManager.kt             (OpKind.BACKUP)
core/ops/BackgroundOpService.kt          (label)
ui/settings/BackupSettings + SettingsScreen route  (settings + album picker + status)
ui/settings/SettingsViewModel.kt         (backup prefs + DeviceAlbums + run trigger)
ui/onboarding/WelcomeScreen.kt           (media permission card)
AndroidManifest.xml                      (READ_MEDIA_IMAGES/VIDEO)
unlock success handler                   (call galleryBackupManager.maybeRun(), beside
                                          the existing maybePrefetchOnUnlock() trigger)
```
