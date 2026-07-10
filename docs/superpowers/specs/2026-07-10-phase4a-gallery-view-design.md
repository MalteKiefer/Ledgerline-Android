# Ledgerline Android — Phase 4a Design: Gallery (view, read-only)

**Date:** 2026-07-10
**Status:** Approved (design)
**Depends on:** Phase 1 (crypto/VK/session/pinning), Phase 2 (store cache pattern),
Phase 3 (secretstream blob download+decrypt).

Phase 4 (Gallery) is decomposed into sub-phases; this spec covers **4a only**:
- **4a (this):** read-only gallery — load the sealed gallery index, render a photo
  grid from decrypted thumbnails, full-photo viewer.
- 4b: photo upload (`/gallery/process` + encrypt renditions + store write).
- 4c: albums + people (face clusters).
- 4d: semantic search (CLIP embed-text + cosine) + map view (osmdroid, no Google).

## 1. Goal

After unlock, load and decrypt the gallery index, show all photos as a scrollable
thumbnail grid (lazy-decrypted), and open a full-screen viewer (medium rendition,
zoom/pan; original on demand). No writes, no albums/people/search/upload in 4a.

## 2. Ground truth (web `resources/js/app.js` `vaultGallery`)

Gallery store blank: `{ v: 1, photos: [], albums: [], people: [] }`. Same sealed
`{ciphertext, version}` envelope as `/store`. A photo entry (tolerate unknown/
missing fields):

```
{ id, media_type /*image|video*/,
  originalRef, originalKey, thumbRef, thumbKey, mediumRef, mediumKey,
  motionRef?, motionKey?, metaRef, metaKey, faceCropRefs:[...],
  sig, lat, lng, width, height, duration, created, trashed, content_id }
```

Each `*Ref` is a blob UUID; each `*Key` is the wrapped per-blob key (the same
`{"c","n"}` `encFileKey` JSON used for files). Blob content is the identical
secretstream format decrypted in Phase 3. `metaRef` (EXIF/embedding/phash/faces)
is NOT needed for 4a (only for 4b/4d).

Transport: `GET /api/v1/gallery/store`, `GET /api/v1/gallery/raw/{blob}`,
`GET /api/v1/gallery/usage`.

## 3. Navigation restructure

The bottom nav changes to four primary tabs: **Files · Gallery · Todos · Notes**.
**Bookmarks** moves into the top-bar ⋮ overflow menu (alongside Settings). Gallery
is a new primary tab.

- `WorkspaceScaffold` tab list becomes `[Files, Gallery, Todos, Notes]`; the ⋮
  overflow gains a "Bookmarks" item (opens the existing `BookmarksScreen`
  full-screen like Settings does) in addition to "Settings".
- The Gallery tab hosts the new `GalleryScreen`.

## 4. Data layer

- `data/remote/dto/GalleryDtos.kt`: reuse `StoreResponse`; add `UsageResponse`
  reuse. (`/gallery/store` returns the same `{ciphertext, version}`.)
- `LedgerlineApi`: `@GET gallery/store`, `@GET @Streaming gallery/raw/{blob}`,
  `@GET gallery/usage`.
- `domain/model/Gallery.kt`: `@Serializable` tolerant models — `GalleryPhoto`,
  `GalleryAlbum`, `GalleryPerson` (albums/people parsed but unused in 4a),
  `GalleryManifest(v, photos, albums, people)`, and a `Gallery(manifest, version)`
  wrapper (mirrors `Workspace`).
- `core/GalleryCache.kt` (`@Singleton`, `StateFlow<Gallery?>`), cleared on lock
  alongside the workspace cache (add `galleryCache.clear()` to `MainActivity`'s
  wipe paths and the `LockGuard`-guarded flow).
- `data/GalleryRepository.kt`: `load(): Outcome<Gallery>` — `GET /gallery/store`,
  `openManifest(ct, vk)` → parse; `null ciphertext` → empty. Uses `SessionHolder`
  + `VaultKeyHolder` + the injectable `apiProvider` seam (as Phase 3).
- **Shared blob decrypt:** extract the Phase-3 download-and-frame-decrypt into a
  reusable `data/BlobDownloader.kt` that, given `(rawResponseBytes, encFileKey, vk)`,
  returns plaintext bytes (the existing `streamDecrypted` logic). `GalleryRepository`
  (or a `GalleryBlobRepository`) uses it for `gallery/raw/{blob}`; `FileBlobRepository`
  is refactored to call the same helper (no behavior change). Add
  `GalleryBlobs.downloadThumb(ref, key)` / `downloadRendition(ref, key)` returning
  decrypted bytes.

## 5. UI

- `ui/gallery/GalleryScreen.kt` + `GalleryViewModel.kt` (`@HiltViewModel`, injects
  `GalleryRepository`/a load use-case + `GalleryCache` + `GalleryBlobs`):
  - On first show, load the gallery index (cache-flow collector like the Files VM);
    pull-to-refresh reloads.
  - `LazyVerticalGrid` (adaptive ~3 columns) of non-trashed photos sorted by
    `created` desc. Each cell lazily requests its thumbnail via
    `downloadThumb(thumbRef, thumbKey)` → decode to `Bitmap` → show; a placeholder
    while loading and on failure. Video cells show a play badge.
  - **Thumbnail cache:** an in-memory LRU (`@Singleton ThumbCache`, bounded ~250
    entries, `photoId → Bitmap`) so scrolling/return doesn't re-download. Cleared on
    lock. (A persistent *encrypted* disk cache is deferred to Phase 5 polish.)
  - Tap a cell → `PhotoViewerScreen`: downloads the `mediumRef` rendition →
    zoom/pan image (pinch + double-tap). A "View original" action downloads
    `originalRef` on demand. Back returns to the grid. Date + place (lat/lng) shown
    when present. Video playback is deferred (4b/later); for a `video` a still +
    "not yet playable" note.
- Empty state (no photos) + error state + a storage-usage line (`gallery/usage`).

## 6. Security

- Decrypted thumbnails/photos live only in memory (LRU + viewer bytes), never
  written to disk in 4a. VK-gated; on lock the gallery cache + thumb cache are
  wiped. Pinned + authenticated. FLAG_SECURE already blocks screenshots.

## 7. Testing

- **Unit:** tolerant `GalleryManifest` parse (unknown/missing fields, unicode);
  `trashed` filter + `created`-desc sort; `GalleryViewModel` states (loading/loaded/
  empty/error) with a fake loader; LRU cache eviction bound.
- **Instrumented:** blob decrypt already covered by Phase-3 `ContentCipherTest`
  (same format) — add a small gallery round-trip only if the shared helper changes
  behavior.
- **On-device smoke:** unlock the real vault, open Gallery → real photos load as a
  grid, open one (medium renders, zoom works), pull-to-refresh.

## 8. File structure

```
data/remote/dto/GalleryDtos.kt              (or reuse StoreResponse/UsageResponse)
data/remote/LedgerlineApi.kt                (+gallery/store, gallery/raw, gallery/usage)
domain/model/Gallery.kt                     (tolerant models + Gallery wrapper)
core/GalleryCache.kt, core/ThumbCache.kt    (new singletons)
data/BlobDownloader.kt                      (shared frame-decrypt helper)
data/GalleryRepository.kt                   (load store)
data/GalleryBlobRepository.kt               (downloadThumb/rendition) + GalleryBlobs seam
domain/usecase/LoadGallery.kt               (seam)
ui/gallery/GalleryScreen.kt, GalleryViewModel.kt, PhotoViewerScreen.kt
ui/workspace/WorkspaceScaffold.kt           (nav: add Gallery tab, Bookmarks→overflow)
di/                                         (bind new repos/usecases)
MainActivity.kt                             (clear GalleryCache/ThumbCache on wipe)
res/values*/strings.xml                     (+gallery strings)
```

## 9. Out of scope (4a)

Photo upload + `/gallery/process`, albums, people/faces, semantic search, map view,
video playback, motion photos, persistent encrypted thumbnail disk cache, gallery
writes/delete/reconcile. Deferred to 4b–4d and Phase 5.
