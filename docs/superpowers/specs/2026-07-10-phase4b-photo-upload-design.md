# Ledgerline Android — Phase 4b Design: Photo Upload

**Date:** 2026-07-10
**Status:** Approved (design)
**Depends on:** Phase 3 (secretstream encrypt + Padmé + streaming upload, 409-merge
pattern), Phase 4a (gallery store/models/cache/blob download).

## 1. Goal

Add photos to the encrypted gallery: pick image(s), upload the encrypted original,
run the stateless server `/gallery/process` on the plaintext to derive renditions
+ metadata, encrypt and upload each derived blob, and append the photo entry to the
gallery index with a 409-merge write. The grid updates live.

## 2. Ground truth (web `vaultGallery._processOne` / `_encStore`)

Per photo:
1. Upload the encrypted original → `originalRef` + `originalKey` (`encFileKey`).
2. `POST /gallery/process` (multipart `file` = plaintext) → JSON
   `{ thumb, medium, motion?, exif, place, embedding, phash, faces:[{score,box,embedding,crop?}], width, height, duration, content_id }`
   (`thumb`/`medium`/`motion`/face `crop` are base64 strings). Server transforms
   in-memory and discards the plaintext.
3. Encrypt + upload `thumb`, `medium`, `motion` → their `*Ref`/`*Key`.
4. For each face with a `crop`: encrypt + upload → `cropRef`/`cropKey` (kept inside
   the meta blob's `faces[]`).
5. Meta blob = `{ exif, place, embedding, phash, faces:[{score,box,embedding,cropRef?,cropKey?}], width, height, duration, content_id }`; encrypt + upload → `metaRef`/`metaKey`.
6. Promote display fields onto the sealed index entry: `taken_at = exif.taken_at || created`,
   `width`, `height`, `duration`, `lat = exif.lat`, `lng = exif.lon`, `camera = exif.camera`,
   `hasFaces`, `faceCropRefs = faces[].cropRef`.
7. Append the entry, `PUT /gallery/store` (409-merge).

Every blob is `encryptContent` (secretstream) + Padmé + `POST /gallery/upload`
(field `file`) → `{ id }` (identical to the Phase-3 file blob upload, different
endpoint). Dedup: `sig` = SHA-256 of the plaintext; skip if an existing photo has
the same `sig`.

## 3. Networking

Add to `LedgerlineApi` (Bearer + pinned):
- `@Multipart @POST gallery/upload → UploadResponse` (`{id}`, reuse Phase-3 DTO).
- `@Multipart @POST gallery/process → ProcessResponse`.
- `@PUT gallery/store {ciphertext, version} → StoreResponse`.

`ProcessResponse` (kotlinx.serialization, opaque fields as `JsonElement` so they
round-trip into the meta blob unchanged):
```
ProcessResponse(thumb, medium, motion: String?, exif: JsonElement?, place: JsonElement?,
  embedding: JsonElement?, phash: String?, faces: List<ProcessFace>, width, height: Int?,
  duration: Double?, content_id: String?)
ProcessFace(score: Double?, box: JsonElement?, embedding: JsonElement?, crop: String?)
```

## 4. Upload pipeline

- **Shared encrypted-upload helper** `data/EncryptedUpload.kt`: builds the streaming
  `RequestBody` that writes `header ++ framed chunks ++ Padmé tail` from an
  `InputStream`/`ByteArray` using `Crypto.newContentEncryptor(vk)` (extracted from
  `FileBlobRepository.upload`; `FileBlobRepository` refactored to use it — no
  behavior change). Returns the `RequestBody` and, after send, the `encFileKey` via
  `encryptor.sealKey()`.
- `data/GalleryBlobRepository` gains:
  - `suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob>`
    (encrypt+pad+`gallery/upload`; `UploadedBlob(id, encFileKey, size)`).
  - `suspend fun process(bytes: ByteArray, name: String, mime: String): Outcome<ProcessResponse>`
    (multipart plaintext → parse).
- `data/GalleryUploader.kt` orchestrates one photo:
  `suspend fun upload(name, mime, sig, bytes): Outcome<GalleryPhoto>` doing steps
  1–6 and returning the fully-populated `GalleryPhoto` (id = new UUID). Base64 via
  `android.util.Base64.NO_WRAP`. Meta JSON assembled with `buildJsonObject` from the
  opaque `exif/place/embedding/phash` + `faces` (with crop refs) + dims. Denorm
  fields read from `exif.jsonObject`.
- `data/GalleryRepository.save(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery>`
  — 409-merge write (mirror `WorkspaceRepository.save`: `sealManifest` → `PUT
  gallery/store`; on 409 reload + re-apply + retry, bounded; update `GalleryCache`).
  A `MutateGallery` seam bound in DI.

## 5. UI

- `GalleryScreen` gains a **FAB** ("Add photos"). Tapping it arms the `LockGuard`
  (so the picker's brief backgrounding doesn't wipe the VK) and launches the Android
  **Photo Picker** (`ActivityResultContracts.PickMultipleVisualMedia`, images; videos
  allowed but playback still deferred).
- `GalleryViewModel.uploadPicked(uris, resolver)` runs a serial queue: for each Uri,
  read bytes (via `contentResolver.openInputStream`), compute `sig` (SHA-256), skip
  if an existing photo shares the `sig`, else `GalleryUploader.upload(...)` →
  `MutateGallery.invoke { it.copy(photos = it.photos + entry) }`. A `busy`/progress
  StateFlow (`n of m`) drives an overlay; on completion refresh usage. Failures per
  photo are collected into a message; the rest continue.
- After each successful append the cache updates → the grid shows the new photo
  (its thumbnail decrypts from the just-uploaded `thumbRef`).

## 6. Security

- Plaintext photo bytes live only in memory and go ONLY to (a) the encryptor and
  (b) `POST /gallery/process` (stateless, discards). No plaintext on disk. Renditions
  are encrypted before upload. VK-gated; LockGuard covers the picker. Padmé on every
  blob. FLAG_SECURE intact.

## 7. Testing

- **Unit:** `ProcessResponse` tolerant parse (opaque exif/place/faces, missing
  fields); meta-JSON assembly (buildJsonObject includes exif/place/embedding/phash +
  faces with crop refs + dims); `sig` dedup skip; `GalleryRepository.save` 409-merge
  (fake api, mirror Phase-3 `WorkspaceSaveTest`); `GalleryUploader` happy path with a
  fake blob repo + fake process returning one thumb/medium + one face.
- **Instrumented:** unchanged crypto (upload uses the verified `ContentCipher`).
- **On-device smoke:** pick a photo → uploads → appears in the grid → open it
  (medium renders) → info shows camera/place; verify it's also visible in the web app
  (byte-interop). Upload the same photo again → deduped (no duplicate).

## 8. File structure

```
data/remote/LedgerlineApi.kt        (+galleryUpload, galleryProcess, galleryStorePut)
data/remote/dto/GalleryDtos.kt      (ProcessResponse, ProcessFace)
data/EncryptedUpload.kt             (shared streaming encrypt+padme RequestBody)
data/FileBlobRepository.kt          (refactor upload -> EncryptedUpload; no behavior change)
data/GalleryBlobRepository.kt       (+uploadBytes, +process)
data/GalleryUploader.kt             (orchestrate one photo)
data/GalleryRepository.kt           (+save(mutate) 409 merge)
domain/usecase/MutateGallery.kt     (seam) + data/MutateGalleryImpl.kt
ui/gallery/GalleryViewModel.kt      (+uploadPicked, progress, dedup)
ui/gallery/GalleryScreen.kt         (+FAB + Photo Picker + progress overlay)
di/WorkspaceModule.kt               (bind MutateGallery)
res/values*/strings.xml             (+upload strings)
```

## 9. Out of scope (4b)

Albums, people-cluster UI, semantic search (4c/4d), video playback, chunked
>2 GB multipart, blob delete/reconcile for gallery (comes with gallery delete
later). Deferred.
