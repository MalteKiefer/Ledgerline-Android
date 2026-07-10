# Ledgerline Android — Phase 3 Design: File Blobs (view / upload / writes)

**Date:** 2026-07-10
**Status:** Approved (design)
**Depends on:** Phase 1 (crypto, VK, session, networking) + Phase 2 (workspace
store, Files tab, `WorkspaceCache`).

## 1. Goal

Turn the read-only Files tab into a working file manager: download + decrypt +
view file content, encrypt + upload new files, and write the workspace manifest
back to the server (create/rename/delete folders and files) with optimistic-
concurrency 409 merge. All crypto is byte-compatible with `resources/js/vault.js`.

## 2. Crypto contract (new primitives, exact to vault.js)

Constants: `CHUNK = 4 MiB`, secretstream `HEADERBYTES = 24`, `ABYTES = 17`,
`secretbox` for key/manifest wrap, base64 `ORIGINAL`.

Add to `Crypto` / `SodiumCrypto`:

- **`sealManifest(json: String, vk: ByteArray): String`** — pad `json` with
  trailing spaces to the next 4096 bucket, `secretbox` seal with a fresh 24-byte
  nonce, return `{"c":base64,"n":base64}`. (Inverse of the Phase-2 `openManifest`.)

- **Content encryption (streaming, constant memory):** a `ContentEncryptor`:
  - `fk = crypto_secretstream_keygen()`, `(state, header) = init_push(fk)`.
  - Blob bytes = `header` ++ for each 4-MiB plaintext slice:
    `cipher = push(state, slice, null, isLast ? TAG_FINAL : TAG_MESSAGE)`,
    frame = `u32le(cipher.size)` ++ `cipher`. Empty input → exactly one final
    empty frame.
  - `sealKey(): String` = `JSON({c,n})` of `secretbox(fk, VK)` (the `encFileKey`).

- **Content decryption (streaming):** a `ContentDecryptor(encFileKey, vk)`:
  - `fk = secretbox_open(encFileKey.c/n, vk)`; `state = init_pull(header, fk)`.
  - Read `u32le` length, then that many bytes → `pull(state, frame)` → message;
    stop at `TAG_FINAL`. Padmé padding after the final frame is never read.

- **Padmé** (`core/crypto/Padme.kt`): `padmeSize(n)` per vault.js
  (`e=floor(log2 n); s=floor(log2 e)+1; bits=e-s; bits<=0 ? n : (n+mask)&~mask`,
  `mask=(1<<bits)-1`). `padBytes(size) = padmeSize(size) - size` random bytes are
  appended to each uploaded blob after the frames.

Byte-parity is validated by an on-device round-trip test (encrypt → decrypt of a
multi-chunk payload and an empty payload) plus unit tests for `padmeSize` and the
framed-size formula `HEADER + total + chunks*(ABYTES+4)`.

## 3. Networking

Add to `LedgerlineApi` (all Bearer + pinned):
- `@GET("api/v1/files/raw/{blob}") @Streaming suspend fun rawFile(@Path blob): Response<ResponseBody>`
- `@Multipart @POST("api/v1/files/upload") suspend fun uploadFile(@Part file: MultipartBody.Part): Response<UploadResponse>` (`UploadResponse(id)`)
- `@DELETE("api/v1/files/blob/{blob}") suspend fun deleteBlob(@Path blob): Response<Unit>`
- `@PUT("api/v1/store") suspend fun putStore(@Body body: StorePutRequest): Response<StoreResponse>` (`StorePutRequest(ciphertext, version)`)
- `@GET("api/v1/files/usage") suspend fun filesUsage(): Response<UsageResponse>` (`UsageResponse(used, quota)`)

Upload uses a streaming `RequestBody` that encrypts + Padmé-pads on the fly, so a
large file is never fully buffered. Download streams the `ResponseBody` source
through the `ContentDecryptor`. Chunked S3-multipart (`/upload/init|part|complete`)
for files above the server's ~2 GB single-body limit is **out of scope** for
Phase 3 (documented; single streaming upload covers the common case).

## 4. Store writes with 409 merge

`WorkspaceRepository.save(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace>`:
1. Start from the cached manifest + version; `next = mutate(current)`.
2. `sealManifest(json(next), vk)` → `PUT /store {ciphertext, version}`.
3. `200` → update cache + version, return Ok.
4. **`409`** → `GET /store`, decrypt to fresh manifest, `next = mutate(fresh)`,
   retry with the fresh version. Bounded to a few attempts; give up → `Err(HTTP)`.

Because every write is expressed as a pure `mutate` over the manifest, replaying
it on the server's fresh version is safe (add-entry, remove-by-id, rename-by-id
all commute cleanly). Blob deletes happen only after the store write succeeds.

## 5. Files UI (read → read/write)

Files tab gains actions (reusing the Phase-2 `FilesViewModel` folder stack):

- **View a file:** tap → `rawFile(blob)` → `ContentDecryptor` → in-memory bytes.
  - Image mime → in-app image viewer (Compose, decode from the in-memory bytes).
  - Text/markdown/`text/*` → in-app text viewer (scrollable, selectable).
  - Other → a detail sheet offering **Save** only.
  - Progress indicator during download/decrypt; errors surfaced with retry.
- **Save (export):** any file → SAF `CreateDocument` → stream decrypt straight to
  the user-chosen `Uri` (no app-side plaintext temp). This is the only way file
  bytes leave the app; it is explicit and user-directed.
- **Upload:** a FAB → SAF `OpenDocument` (and Photo Picker for images) → read
  name/mime/size → stream-encrypt+pad → `uploadFile` → append manifest entry
  `{id, blob:id, encFileKey, name, mime, size, folder:cwd, created}` → `save`.
  Upload progress shown; on failure nothing is written to the manifest.
- **New folder:** dialog → append `fileFolders` entry `{id, name, parent:cwd}` →
  `save`.
- **Rename:** file or folder → dialog → `save` (mutate the entry's `name`).
- **Delete:** file → remove entry → `save` → `deleteBlob(blob)` (throttled,
  429-aware backoff). Folder → recurse: collect the subtree's file blobs + folder
  ids, remove all from the manifest → `save` → delete each freed blob throttled.
- **Usage:** show `used / quota` from `filesUsage()`.

Confirm destructive actions (delete) with a dialog.

## 6. Security

- Decrypted file bytes live in memory only; released promptly after view/save.
- SAF export streams decrypt → chosen `Uri` chunk-by-chunk; no plaintext temp file
  is ever created on internal/external storage.
- No "open with external app" (would require a plaintext temp) in Phase 3.
- All calls VK-gated + pinned; VK/cache wiped on lock as in Phase 2.
- Blob deletes throttled (max ~4 concurrent, honor `Retry-After`).

## 7. Testing

- **Instrumented (device):** content encrypt→decrypt round-trip (multi-chunk +
  empty); `encFileKey` wrap/unwrap; Padmé does not corrupt decryption.
- **Unit:** `padmeSize` vectors; framed-size formula; `WorkspaceRepository.save`
  happy path + 409-merge-retry (fake API); upload/download stream against
  MockWebServer; delete-throttle 429 backoff; FilesViewModel actions/state.
- **On-device smoke:** upload a photo + a text file to the live server, see them
  appear, view them, save one via SAF, delete one, create/rename a folder.

## 8. File structure

```
core/crypto/Crypto.kt, SodiumCrypto.kt      (+sealManifest, content enc/dec)
core/crypto/Padme.kt                        (new)
core/crypto/ContentCipher.kt                (new — streaming encryptor/decryptor)
data/remote/LedgerlineApi.kt                (+raw/upload/delete/putStore/usage)
data/remote/dto/FilesDtos.kt                (UploadResponse, StorePutRequest, UsageResponse)
data/WorkspaceRepository.kt                 (+save(mutate) with 409 merge)
data/FileBlobRepository.kt                  (new — download/decrypt, upload/encrypt, delete)
domain/usecase/{UploadFile,DownloadFile,DeleteFile,MutateWorkspace}.kt  (new seams)
ui/workspace/files/FilesViewModel.kt        (+actions: upload/delete/rename/newFolder/usage)
ui/workspace/files/FilesScreen.kt           (FAB, actions, dialogs)
ui/workspace/files/FileViewerScreen.kt      (new — image/text viewer)
ui/workspace/files/FileActions.kt           (new — SAF launchers, save/export)
```
Modify: `di/` (bind new repos/usecases), strings (en+de).

## 9. Out of scope (Phase 3)

File versions/history, move/reorder between folders, chunked >2 GB multipart
upload, external "open with", gallery. Deferred to later phases.
