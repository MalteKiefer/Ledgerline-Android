# Phase 4c — Albums + People (on-device face clustering)

**Goal:** Add album management and on-device face-cluster "People" to the gallery,
mirroring the web client's behaviour, with the gallery split into three views
(Photos | Albums | People) via a segmented control.

**Ground truth:** `resources/js/app.js` in the ledgerline repo — `vaultGallery`
component: album ops (~L1390–1436), people/face clustering (`scanFaces`,
`_faceClustersInline` ~L1548–1687), `cosine`/`_norm`/`_dot`/`_ensureMeta`
(~L1280–1320). The manifest schema is additive; the web tolerates unknown fields.
Clustering is an app heuristic (NOT the frozen crypto/transport contract) but must
mirror the web thresholds so results agree across clients.

---

## 1. Model changes (`domain/model/Gallery.kt`)

Add a per-person face reference and give `GalleryPerson` a `faces` list:

```kotlin
@Serializable
data class PersonFace(
    val photoId: String = "",
    val idx: Int = 0,
    val cropRef: String? = null,
    val cropKey: String? = null,
)

@Serializable
data class GalleryPerson(
    val id: String = "", val name: String = "", val hidden: Boolean = false,
    val centroid: List<Double> = emptyList(),
    val faces: List<PersonFace> = emptyList(),   // NEW
)
```

Extend the decrypted meta blob so clustering can read embeddings + faces. The meta
JSON is `{ exif, place, embedding, phash, faces:[{embedding, box, score, cropRef,
cropKey}], ... }`. Keep tolerant (`ignoreUnknownKeys`), opaque where unused:

```kotlin
@Serializable
data class MetaFace(
    val embedding: List<Double> = emptyList(),
    val cropRef: String? = null,
    val cropKey: String? = null,
)

@Serializable
data class PhotoMetaBlob(
    val place: PhotoPlace? = null,
    val embedding: List<Double> = emptyList(),   // NEW (CLIP, for later search)
    val faces: List<MetaFace> = emptyList(),      // NEW
)
```

(`phash`/`box`/`score` are not needed for 4c clustering; leave them out — tolerant
parsing ignores them.)

## 2. MetaCache (`core/MetaCache.kt`) — security-relevant

Session-only, in-memory, `@Singleton`. Maps `photoId -> PhotoMetaBlob?` so each
sealed meta blob is fetched + decrypted at most once per session. Mirrors the web
`metaCache` closure. **Holds plaintext CLIP embeddings + face data → MUST be wiped
on lock/logout.** Add `metaCache.clear()` to `ForceLogoutImpl.invoke()` and to the
`MainActivity` lifecycle wipe (alongside `galleryCache.clear()`), and inject it into
`ForceLogoutImpl`.

```kotlin
@Singleton
class MetaCache @Inject constructor() {
    private val map = java.util.Collections.synchronizedMap(HashMap<String, PhotoMetaBlob?>())
    fun get(id: String): PhotoMetaBlob? = map[id]
    fun has(id: String): Boolean = map.containsKey(id)
    fun put(id: String, meta: PhotoMetaBlob?) { map[id] = meta }
    fun clear() { map.clear() }
}
```

## 3. FaceClusterer (`domain/gallery/FaceClusterer.kt`) — pure Kotlin, unit-tested

Exact port of `_faceClustersInline`. No Android deps. Inputs/outputs are plain data:

```kotlin
data class FaceInput(val emb: List<Double>, val member: PersonFace)
data class SeedCluster(val id: String, val name: String, val hidden: Boolean,
                       val centroid: List<Double>, val members: List<PersonFace>)
data class PrevPerson(val name: String, val hidden: Boolean, val centroid: List<Double>)
// result person carries id=null for brand-new clusters (caller assigns an id)
data class ClusterResult(val id: String?, val name: String, val hidden: Boolean,
                         val centroid: List<Double>, val faces: List<PersonFace>)
```

Algorithm (byte-for-byte with the web):
- `cosine(a,b)` = `dot/(sqrt(na)*sqrt(nb))`, `0.0` if either norm is 0, using
  `n = min(a.size, b.size)`.
- Seed `clusters` from `seeds` (id/name/hidden/centroid copy/count=members.size/
  members). Mark each seed member `"photoId:idx"` in a `placed` set.
- For each face in input order: key `"photoId:idx"`; skip if already `placed`; else
  add. `best=null, bestSim=0.5`; pick the cluster with the highest cosine strictly
  `> bestSim`. If found: update centroid in place `c[i]=(c[i]*n + emb[i])/(n+1)` for
  `i in 0 until centroid.size`, `count=n+1`, append member. Else new cluster
  `{id=null,name="",hidden=false,centroid=emb.copy,count=1,members=[member]}`.
- Keep clusters with `members.size >= 2`, sort by `members.size` descending.
- For each surviving cluster, if `!incremental`: match against `prev` centroids with
  `bestSim=0.6` (strictly `>`) and carry `name`/`hidden` from the best match.
- Emit `ClusterResult(id, name, hidden, centroid, faces=members)`.

Iterate faces in a stable order: photos in library order (see §5 targets), each
photo's `meta.faces` in index order.

## 4. Albums — `AlbumsViewModel` + UI

`AlbumsViewModel` (Hilt) reads `GalleryCache.value` reactively, exposes sorted
albums (by `name`, `localeCompare`-ish → `compareBy(String.CASE_INSENSITIVE_ORDER)`),
album photos (`photoIds` ∩ non-trashed library photos, preserving library order),
cover photo (photo matching `cover` else first album photo), and count. Writes via
`MutateGallery`:
- **create**(name, photoIds): append `GalleryAlbum(id=UUID, name, photoIds, cover=photoIds.first(), created=nowIso)`.
- **rename**(album, name), **delete**(album).
- **addPhotos**(album, ids): union into `photoIds`; set cover if empty.
- **removePhoto**(album, photoId): drop from `photoIds`; if `cover==photoId`, cover=first remaining.
- **setCover**(album, photoId).

UI: albums grid (cover thumb via existing `GalleryViewModel.thumb`/`ThumbCache` — the
cover is a `GalleryPhoto`, reuse the thumb path), tap → album photo grid (reuse
`ThumbCell` + open `PhotoViewerScreen`). Overflow per album: rename, delete. In the
album grid, a photo's overflow: remove from album, set as cover.

**Selection mode** in the Photos view: long-press a thumb → selection mode (checkable
thumbs, top action bar). Actions: "New album from selection", "Add to album…"
(pick existing). Exit clears selection.

## 5. People — `PeopleViewModel` + UI

State: `people` (from `GalleryCache`, filter `!hidden && personPhotos.isNotEmpty()`),
`scanning: Boolean`, `progress: (done,total)`, `scanLimit: Int` (0 = whole library).

`personPhotos(pp)` = distinct `faces[].photoId` mapped to non-trashed library photos
(library order). `personCover(pp)` = `faces.first()` (a `PersonFace` → its cropRef/
cropKey → face thumbnail). Face thumbnails: download+decrypt `cropRef` with `cropKey`
via `GalleryBlobs.download`, decode to bitmap, cache in `ThumbCache` keyed by
`cropRef` (crop refs are UUIDs, disjoint from photo ids).

`scanFaces()` (mirror of web `scanFaces`), on `Dispatchers.Default`, progress on Main:
- `targets` = `_scanTargets()`: if `scanLimit==0` → all non-trashed photos (library
  order); else the `scanLimit` most-recent by `created` desc.
- `incremental = scanLimit > 0`.
- ensure meta for targets: for each target with `metaRef` and not in `MetaCache`,
  `GalleryBlobs.download(metaRef, metaKey)` → parse `PhotoMetaBlob` (tolerant) → put
  in `MetaCache` (null on failure); report progress `(done,total)`.
- collect `FaceInput` for every target's `meta.faces` where `embedding` non-empty and
  `cropRef != null`: `PersonFace(photoId=p.id, idx, cropRef, cropKey)`.
- `seeds` (incremental only) from existing people with non-empty centroid:
  `SeedCluster(id, name, hidden, centroid, members=faces)`. `prev` (full scan only)
  from existing people with non-empty centroid: `PrevPerson(name, hidden, centroid)`.
- `built = FaceClusterer.cluster(faces, seeds, prev, incremental)`; assign a new UUID
  to any result with `id==null`.
- if incremental: re-append any existing person not in `built` whose `faces.size>=2`.
- write via `MutateGallery`: `manifest.copy(people = built as GalleryPerson[])`.

UI: people grid (face-crop thumb + name or "Unbenannt"/"Unnamed" + photo count),
tap → person photo grid (reuse ThumbCell + viewer). A "Gesichter scannen" action
(top bar / overflow) with a scope choice (whole library vs. recent N — a simple
dialog or two menu items: "Alles scannen" / "Nur neue (letzte 200)"). Progress
overlay like the upload overlay. Per-person overflow: rename (text dialog), hide.

## 6. Navigation (`GalleryScreen`)

Add a segmented control (Material3 `SingleChoiceSegmentedButtonRow`) pinned at the
top: **Fotos | Alben | People**. A `rememberSaveable` `GalleryTab` enum switches the
body between the existing photo grid, `AlbumsScreen`, and `PeopleScreen`. The FAB
(add/camera) shows only on the Photos tab. Album/person detail and the photo viewer
remain full-screen (reuse the existing `openId`/return pattern).

## 7. Strings (de + en)

New keys (both `values/` and `values-de/`): tabs (`gallery_tab_photos/albums/people`),
album actions (`album_new`, `album_add_to`, `album_rename`, `album_delete`,
`album_name_hint`, `album_remove_photo`, `album_set_cover`, `albums_empty`),
people (`people_empty`, `people_scan`, `people_scan_all`, `people_scan_recent`,
`people_scanning`, `person_unnamed`, `person_rename`, `person_hide`,
`person_photos_count`), selection (`selection_count`, `action_cancel`).

## 8. Testing

- `FaceClustererTest` (JVM): full scan groups two near-identical embeddings into one
  cluster and keeps a lone face out (min 2); incremental scan seeds from an existing
  person and merges a new matching face; name carry-over via `prev` on a full scan;
  cosine edge cases (zero vector → 0). This is the核心 correctness gate.
- `MetaCacheTest` (JVM): put/get/has/clear.
- Album mutation tests where practical (pure manifest transforms could be extracted
  to a small `AlbumOps` object and unit-tested: create/add/remove/cover invariants).
- On-device smoke: build, install, scan faces on the real library, confirm people
  appear and open, albums create/open/delete, no crash; and confirm lock/logout
  wipes the MetaCache (no plaintext retained).

## 9. Security checklist (this phase)

- MetaCache holds plaintext embeddings/faces → wiped on `ForceLogoutImpl` **and**
  MainActivity lock lifecycle. Never persisted, never logged.
- Face crops decrypted only in-memory (ThumbCache, already memory-only).
- No new network endpoints; clustering is fully on-device.
- No raw embeddings/crops in logcat.
