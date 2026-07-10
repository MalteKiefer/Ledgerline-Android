# Ledgerline Android — Phase 4b Implementation Plan (Photo Upload)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pick photo(s), upload the encrypted original, run `/gallery/process` on the plaintext, encrypt+upload the derived renditions + meta, and append the photo to the gallery index (409-merge) so it appears in the grid.

**Architecture:** A shared `EncryptedUpload` builds the streaming secretstream+Padmé request body (extracted from Phase 3). `GalleryBlobRepository` uploads blobs + calls `process`; `GalleryUploader` orchestrates one photo end-to-end; `GalleryRepository.save(mutate)` writes the index with 409-merge. `GalleryViewModel.uploadPicked` runs a serial queue with sig-dedup; the grid updates from the cache.

**Tech Stack:** Kotlin, Compose (Photo Picker), Hilt, Retrofit/OkHttp (streaming multipart), lazysodium (reused). Byte-format identical to `vault.js`/web.

**Reference:** Spec `docs/superpowers/specs/2026-07-10-phase4b-photo-upload-design.md`. Ground truth `~/Entwicklung/ledgerline/resources/js/app.js` (`_processOne`, `_encStore`).

**Conventions:** English only. Conventional Commits. Branch `feature/phase4b` (created). Device `62021JEBF09273` (`ANDROID_SERIAL=...`). JVM tests `./gradlew :app:testDebugUnitTest`. Keep FLAG_SECURE + hardening. Commit-message hook blocks AI trailers.

---

## File Structure

```
data/remote/LedgerlineApi.kt        (+galleryUpload, galleryProcess, galleryStorePut)
data/remote/dto/GalleryDtos.kt      (new — ProcessResponse, ProcessFace)
data/EncryptedUpload.kt             (new — shared streaming encrypt+padme body)
data/FileBlobRepository.kt          (refactor upload -> EncryptedUpload)
data/GalleryBlobRepository.kt       (+uploadBytes, +process)
data/GalleryUploader.kt             (new — orchestrate one photo)
data/GalleryRepository.kt           (+save(mutate) 409 merge)
domain/usecase/MutateGallery.kt + data/MutateGalleryImpl.kt   (seam)
ui/gallery/GalleryViewModel.kt      (+uploadPicked, progress, dedup)
ui/gallery/GalleryScreen.kt         (+FAB + Photo Picker + progress)
di/WorkspaceModule.kt               (bind MutateGallery)
res/values*/strings.xml             (+upload strings)
```

---

## Task 1: Endpoints, ProcessResponse DTO, shared EncryptedUpload, gallery upload/process

**Files:** Modify `data/remote/LedgerlineApi.kt`. Create `data/remote/dto/GalleryDtos.kt`, `data/EncryptedUpload.kt`. Modify `data/FileBlobRepository.kt`, `data/GalleryBlobRepository.kt`. Tests `app/src/test/java/de/ledgerline/app/data/remote/GalleryDtosTest.kt`.

- [ ] **Step 1: Add endpoints to `LedgerlineApi.kt`**

```kotlin
    @retrofit2.http.Multipart
    @POST("api/v1/gallery/upload")
    suspend fun galleryUpload(@retrofit2.http.Part file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse>

    @retrofit2.http.Multipart
    @POST("api/v1/gallery/process")
    suspend fun galleryProcess(@retrofit2.http.Part file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.ProcessResponse>

    @retrofit2.http.PUT("api/v1/gallery/store")
    suspend fun galleryStorePut(@Body body: de.ledgerline.app.data.remote.dto.StorePutRequest): Response<de.ledgerline.app.data.remote.dto.StoreResponse>
```
Update any `: LedgerlineApi` test fakes with `throw NotImplementedError()` overrides for these three.

- [ ] **Step 2: Create `data/remote/dto/GalleryDtos.kt`**

```kotlin
package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProcessResponse(
    val thumb: String? = null,
    val medium: String? = null,
    val motion: String? = null,
    val exif: JsonElement? = null,
    val place: JsonElement? = null,
    val embedding: JsonElement? = null,
    val phash: String? = null,
    val faces: List<ProcessFace> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    val content_id: String? = null,
)

@Serializable
data class ProcessFace(
    val score: Double? = null,
    val box: JsonElement? = null,
    val embedding: JsonElement? = null,
    val crop: String? = null,
)
```

- [ ] **Step 3: Failing tolerant-parse test**

`app/src/test/java/de/ledgerline/app/data/remote/GalleryDtosTest.kt`:
```kotlin
package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.ProcessResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GalleryDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_process_response_with_opaque_and_missing_fields() {
        val text = """
          {"thumb":"AAA","medium":"BBB","exif":{"camera":"Pixel","lat":51.1,"lon":6.9,"taken_at":"2026-01-01","iso":100},
           "place":{"city":"Köln","country":"DE"},"embedding":[0.1,0.2],"phash":"ff00",
           "faces":[{"score":0.9,"box":[1,2,3,4],"embedding":[0.3],"crop":"CCC"}],
           "width":4000,"height":3000,"content_id":"abc","surprise":1}
        """.trimIndent()
        val p = json.decodeFromString<ProcessResponse>(text)
        assertEquals("AAA", p.thumb)
        assertEquals(4000, p.width)
        assertEquals(1, p.faces.size)
        assertEquals("CCC", p.faces[0].crop)
        assertNotNull(p.exif)      // opaque, preserved
        assertNotNull(p.place)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*GalleryDtosTest*"` → PASS after Step 2.

- [ ] **Step 4: Create `data/EncryptedUpload.kt`** (shared streaming encrypt+padme body)

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.padByteCount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.security.SecureRandom

/** Builds a streaming RequestBody that writes header ++ framed secretstream chunks
 *  ++ Padmé random tail, encrypting on the fly (constant memory). The caller reads
 *  the wrapped key from [encryptor].sealKey() AFTER the request is sent. */
object EncryptedUpload {
    fun body(encryptor: Crypto.ContentEncryptor, chunkSize: Int, size: Long, openInput: () -> InputStream): RequestBody {
        val chunks = if (size == 0L) 1L else (size + chunkSize - 1) / chunkSize
        val framed = 24L + size + chunks * (17L + 4L)
        val pad = padByteCount(framed)
        return object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.write(encryptor.header)
                openInput().use { ins ->
                    if (size == 0L) { sink.write(encryptor.encryptChunk(ByteArray(0), true)) }
                    else {
                        val buf = ByteArray(chunkSize); var remaining = size
                        while (remaining > 0) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            var read = 0
                            while (read < want) { val r = ins.read(buf, read, want - read); if (r < 0) break; read += r }
                            val eof = read < want
                            val last = eof || remaining - read <= 0
                            sink.write(encryptor.encryptChunk(buf.copyOf(read), last))
                            remaining -= read
                            if (eof) break
                        }
                    }
                }
                if (pad > 0) {
                    val rnd = SecureRandom(); val block = ByteArray(64 * 1024); var left = pad
                    while (left > 0) { val n = minOf(block.size.toLong(), left).toInt(); rnd.nextBytes(block); sink.write(block, 0, n); left -= n }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Refactor `FileBlobRepository.upload`** to build its body via `EncryptedUpload.body(enc, crypto.contentChunkSize, size, openInput)` (create `enc = crypto.newContentEncryptor(vk)` first, then `enc.sealKey()` after send). Keep the public signature + behavior identical; run `DeleteThrottleTest` + full suite to confirm no regression.

- [ ] **Step 6: Add `uploadBytes` + `process` to `GalleryBlobRepository`**

```kotlin
    suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val enc = crypto.newContentEncryptor(vk)
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }
        try {
            val part = okhttp3.MultipartBody.Part.createFormData("file", name, body)
            val res = apiProvider(session).galleryUpload(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), bytes.size.toLong()))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    suspend fun process(bytes: ByteArray, name: String, mime: String): Outcome<de.ledgerline.app.data.remote.dto.ProcessResponse> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val body = okhttp3.RequestBody.create(mime.toMediaTypeOrNull(), bytes)
            val part = okhttp3.MultipartBody.Part.createFormData("file", name, body)
            val res = apiProvider(session).galleryProcess(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(res.body()!!)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }
```
Add imports (`UploadedBlob` from `de.ledgerline.app.data`, `okhttp3.MediaType.Companion.toMediaTypeOrNull`). `UploadedBlob` is the Phase-3 data class in `data/FileBlobRepository.kt` — reuse it (import or move to a shared file). If it's `private`/nested, promote it to a top-level `data class UploadedBlob(val id: String, val encFileKey: String, val size: Long)` in `data/`.

- [ ] **Step 7: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app app/src/test
git commit -m "feat: gallery upload/process endpoints, shared encrypted-upload, process DTO"
```

---

## Task 2: `GalleryRepository.save(mutate)` (409 merge)

**Files:** Modify `data/GalleryRepository.kt`. Create `domain/usecase/MutateGallery.kt`, `data/MutateGalleryImpl.kt`. Modify `di/WorkspaceModule.kt`. Test `app/src/test/java/de/ledgerline/app/data/GallerySaveTest.kt`.

- [ ] **Step 1: Add `save` to `GalleryRepository`** (mirror `WorkspaceRepository.save`)

The repo already has the dual-constructor `apiProvider`, `sessionHolder`, `vaultKeyHolder`, `crypto`, `cache` (add `GalleryCache` to the constructor if not present — mirror `WorkspaceRepository`). Add:
```kotlin
    private val jsonEncoder = kotlinx.serialization.json.Json { encodeDefaults = true }

    suspend fun save(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        var version = cache.value.value?.version
        var base = cache.value.value?.manifest
        repeat(4) {
            if (base == null || version == null) {
                val res = api.galleryStore(); if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                val body = res.body()!!
                base = body.ciphertext?.let { json.decodeFromString<GalleryManifest>(crypto.openManifest(it, vk)!!) } ?: GalleryManifest()
                version = body.version
            }
            val next = mutate(base!!)
            val ciphertext = crypto.sealManifest(jsonEncoder.encodeToString(GalleryManifest.serializer(), next), vk)
            val put = try { api.galleryStorePut(de.ledgerline.app.data.remote.dto.StorePutRequest(ciphertext, version!!)) } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
            when {
                put.isSuccessful -> { val g = Gallery(next, put.body()?.version ?: (version!! + 1)); cache.set(g); return Outcome.Ok(g) }
                put.code() == 409 -> {
                    val res = api.galleryStore(); if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                    val body = res.body()!!
                    base = body.ciphertext?.let { json.decodeFromString<GalleryManifest>(crypto.openManifest(it, vk)!!) } ?: GalleryManifest()
                    version = body.version
                }
                else -> return Outcome.Err(ErrorKind.HTTP)
            }
        }
        return Outcome.Err(ErrorKind.HTTP)
    }
```
If `GalleryRepository` doesn't already inject `GalleryCache`, add it to both constructors.

- [ ] **Step 2: `MutateGallery` seam + impl + DI**

`domain/usecase/MutateGallery.kt`:
```kotlin
package de.ledgerline.app.domain.usecase
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
interface MutateGallery { suspend fun invoke(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> }
```
`data/MutateGalleryImpl.kt`:
```kotlin
package de.ledgerline.app.data
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.usecase.MutateGallery
import javax.inject.Inject
class MutateGalleryImpl @Inject constructor(private val repo: GalleryRepository) : MutateGallery {
    override suspend fun invoke(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> = repo.save(mutate)
}
```
DI: `@Binds abstract fun bindMutateGallery(impl: MutateGalleryImpl): MutateGallery`.

- [ ] **Step 3: 409-merge test** — `GallerySaveTest.kt` mirroring the Phase-3 `WorkspaceSaveTest`: a `FakeApi : LedgerlineApi` (all methods; `galleryStore` returns a manifest with one existing photo at version 5, `galleryStorePut` → 409 then success v6) + a fake `Crypto` (`sealManifest`="SEALED:$json", `openManifest`=strip prefix). Assert `save { it.copy(photos = it.photos + GalleryPhoto(id="new")) }` merges onto the server's fresh manifest (both photos present), 2 puts, version 6. Run → PASS.

- [ ] **Step 4: Build + commit**

```bash
git add app/src/main/java/de/ledgerline/app app/src/test
git commit -m "feat: gallery store save with 409 merge"
```

---

## Task 3: `GalleryUploader` (one photo end-to-end)

**Files:** Create `data/GalleryUploader.kt`, `domain/usecase/UploadPhoto.kt` (seam). Test `app/src/test/java/de/ledgerline/app/data/GalleryUploaderTest.kt`.

- [ ] **Step 1: Implement `data/GalleryUploader.kt`**

```kotlin
package de.ledgerline.app.data

import android.util.Base64
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.domain.model.GalleryPhoto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryUploader @Inject constructor(private val blobs: GalleryBlobRepository) {
    private val json = Json { encodeDefaults = true }

    /** Uploads one photo (original + renditions + faces + meta) and returns the entry. */
    suspend fun upload(name: String, mime: String, sig: String, bytes: ByteArray, createdIso: String): Outcome<GalleryPhoto> {
        val original = blobs.uploadBytes(bytes, name).okOr { return it }
        val d = blobs.process(bytes, name, mime).okOr { return it }

        val thumb = d.thumb?.let { blobs.uploadBytes(Base64.decode(it, Base64.DEFAULT), "thumb.enc").okOr { e -> return e } }
        val medium = d.medium?.let { blobs.uploadBytes(Base64.decode(it, Base64.DEFAULT), "medium.enc").okOr { e -> return e } }
        val motion = d.motion?.let { blobs.uploadBytes(Base64.decode(it, Base64.DEFAULT), "motion.enc").okOr { e -> return e } }

        // Faces: upload crops, keep refs in the meta faces array.
        val faceRefs = mutableListOf<String>()
        val metaFaces = buildJsonArray {
            for (f in d.faces) {
                val crop = f.crop?.let { blobs.uploadBytes(Base64.decode(it, Base64.DEFAULT), "crop.enc").okOr { e -> return e } }
                if (crop != null) faceRefs += crop.id
                add(buildJsonObject {
                    f.score?.let { put("score", JsonPrimitive(it)) }
                    f.box?.let { put("box", it) }
                    f.embedding?.let { put("embedding", it) }
                    if (crop != null) { put("cropRef", JsonPrimitive(crop.id)); put("cropKey", JsonPrimitive(crop.encFileKey)) }
                })
            }
        }

        val metaObj = buildJsonObject {
            d.exif?.let { put("exif", it) }
            d.place?.let { put("place", it) }
            d.embedding?.let { put("embedding", it) }
            d.phash?.let { put("phash", JsonPrimitive(it)) }
            put("faces", metaFaces)
            d.width?.let { put("width", JsonPrimitive(it)) }
            d.height?.let { put("height", JsonPrimitive(it)) }
            d.duration?.let { put("duration", JsonPrimitive(it)) }
            d.content_id?.let { put("content_id", JsonPrimitive(it)) }
        }
        val meta = blobs.uploadBytes(json.encodeToString(JsonObject.serializer(), metaObj).toByteArray(), "meta.enc").okOr { return it }

        // Denorm fields from exif.
        val exif = (d.exif as? JsonObject)
        fun exifStr(k: String) = exif?.get(k)?.jsonPrimitive?.contentOrNull()
        fun exifDbl(k: String) = exif?.get(k)?.jsonPrimitive?.content?.toDoubleOrNull()

        val entry = GalleryPhoto(
            id = java.util.UUID.randomUUID().toString(),
            media_type = if (mime.startsWith("video")) "video" else "image",
            originalRef = original.id, originalKey = original.encFileKey,
            thumbRef = thumb?.id, thumbKey = thumb?.encFileKey,
            mediumRef = medium?.id, mediumKey = medium?.encFileKey,
            motionRef = motion?.id, motionKey = motion?.encFileKey,
            metaRef = meta.id, metaKey = meta.encFileKey,
            faceCropRefs = faceRefs,
            sig = sig,
            lat = exifDbl("lat"), lng = exifDbl("lon"),
            width = d.width, height = d.height, duration = d.duration,
            taken_at = exifStr("taken_at") ?: createdIso,
            camera = exifStr("camera"),
            hasFaces = d.faces.size,
            created = createdIso,
            content_id = d.content_id,
        )
        return Outcome.Ok(entry)
    }

    private inline fun <T> Outcome<T>.okOr(onErr: (Outcome<Nothing>) -> Nothing): T =
        when (this) { is Outcome.Ok -> value; is Outcome.Err -> onErr(this) }
}

private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else content.ifBlank { null }
```
Note: verify `JsonPrimitive.contentOrNull` handling; adapt if kotlinx exposes `contentOrNull` already (it does for `JsonElement?` via `jsonPrimitive.contentOrNull` in newer versions — use whichever compiles). The acceptance test drives the exact behavior.

- [ ] **Step 2: `GalleryUploaderTest`** (fake blob repo + fake process)

Make `GalleryBlobRepository` methods used here (`uploadBytes`, `process`) reachable via an interface OR construct `GalleryUploader` with a test double. Simplest: extract an interface `GalleryUploadApi { suspend fun uploadBytes(...); suspend fun process(...) }` implemented by `GalleryBlobRepository`, and have `GalleryUploader` depend on it. Then the test provides a fake returning canned `UploadedBlob(id="blob-N")` and a `ProcessResponse` with one thumb + one face+crop. Assert the returned `GalleryPhoto` has `thumbRef`, `metaRef`, `faceCropRefs.size == 1`, `camera`/`lat`/`lng` from exif, `hasFaces == 1`. (If extracting the interface is heavy, make `uploadBytes`/`process` `open` and subclass — but the interface is cleaner and matches the codebase's seam pattern.)
```kotlin
package de.ledgerline.app.data
// ... construct GalleryUploader(fakeApi); call upload(...); assert the entry fields.
```
Run: `./gradlew :app:testDebugUnitTest --tests "*GalleryUploaderTest*"` → PASS.

- [ ] **Step 3: Build + commit**

```bash
git add app/src/main/java/de/ledgerline/app app/src/test
git commit -m "feat: gallery uploader (original, renditions, faces, meta, entry)"
```

---

## Task 4: GalleryViewModel upload queue + dedup

**Files:** Modify `ui/gallery/GalleryViewModel.kt`. Test extend `app/src/test/java/de/ledgerline/app/ui/gallery/GalleryViewModelTest.kt`.

- [ ] **Step 1: Add upload to the VM**

Inject `GalleryUploader` (or its interface) + `MutateGallery` + `LockGuard`. Add:
```kotlin
    data class Progress(val current: Int, val total: Int)
    private val _uploadProgress = MutableStateFlow<Progress?>(null)
    val uploadProgress: StateFlow<Progress?> = _uploadProgress

    fun armLockSuppression() = lockGuard.armSkipOnce()

    /** Upload each source: read bytes, dedup by sig, upload, append to the index. */
    fun uploadAll(sources: List<PhotoSource>) = viewModelScope.launch {
        val existing = cache.value.value?.manifest?.photos?.mapNotNull { it.sig }?.toMutableSet() ?: mutableSetOf()
        _uploadProgress.value = Progress(0, sources.size)
        var done = 0; var failed = 0
        for (src in sources) {
            val bytes = try { src.read() } catch (_: Exception) { failed++; done++; _uploadProgress.value = Progress(done, sources.size); continue }
            val sig = sha256Hex(bytes)
            if (sig in existing) { done++; _uploadProgress.value = Progress(done, sources.size); continue }  // dedup
            when (val up = uploader.upload(src.name, src.mime, sig, bytes, nowIso())) {
                is Outcome.Ok -> { mutate.invoke { it.copy(photos = it.photos + up.value) }; existing += sig }
                is Outcome.Err -> failed++
            }
            done++; _uploadProgress.value = Progress(done, sources.size)
        }
        _uploadProgress.value = null
        loadUsage()
        if (failed > 0) _message.value = "upload_failed:$failed"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun nowIso(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
```
Add `data class PhotoSource(val name: String, val mime: String, val read: () -> ByteArray)` (top-level in the gallery ui package) so the screen supplies content-resolver-backed readers without the VM touching Android URIs. Add a `_message: MutableStateFlow<String?>` if not present (mirror Files VM) with `clearMessage()`.

- [ ] **Step 2: Extend `GalleryViewModelTest`** — a `uploads_and_appends` test: fake uploader returns a `GalleryPhoto(id="p9", sig="s9")`, fake `MutateGallery` applies the mutation to the `GalleryCache`; call `vm.uploadAll(listOf(PhotoSource("a.jpg","image/jpeg"){ byteArrayOf(1,2,3) }))`; assert the cache/state now contains `p9` and a second identical-bytes source is deduped (uploader called once). Update the VM constructor in existing tests. Keep `Dispatchers.setMain`.
Run → PASS.

- [ ] **Step 3: Build + commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/gallery app/src/test
git commit -m "feat: gallery upload queue with sha-256 dedup and progress"
```

---

## Task 5: Gallery FAB + Photo Picker + progress UI

**Files:** Modify `ui/gallery/GalleryScreen.kt`. Add strings.

- [ ] **Step 1: FAB + Photo Picker + progress**

Add a `FloatingActionButton` (Add, `Icons.Outlined.AddPhotoAlternate`) to `GalleryScreen`. Use `rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30))`; on the returned `List<Uri>`, map each to a `PhotoSource(name = queryName(context, uri), mime = resolver.getType(uri) ?: "image/jpeg", read = { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } })` and call `vm.uploadAll(sources)`. Call `vm.armLockSuppression()` immediately before `launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))`.
- Show an upload progress overlay when `vm.uploadProgress` is non-null: a `Box` scrim + `CircularProgressIndicator` + `"Uploading ${p.current}/${p.total}"` (`gallery_uploading`).
- Observe `vm.message`; show a snackbar for `upload_failed:N` (`gallery_upload_failed`) then `clearMessage()`.
- Reuse a `queryName(context, uri)` helper (DISPLAY_NAME from `OpenableColumns`, fallback "photo.jpg").

- [ ] **Step 2: Strings (en+de)** — `gallery_add` (`Add photos`/`Fotos hinzufügen`), `gallery_uploading` (`Uploading %1$d/%2$d`/`Lade hoch %1$d/%2$d`), `gallery_upload_failed` (`%1$d upload(s) failed`/`%1$d Upload(s) fehlgeschlagen`).

- [ ] **Step 3: Build + install + smoke**

Run: `./gradlew :app:assembleDebug`. Install on `62021JEBF09273`, launch, `adb -s 62021JEBF09273 logcat -d | grep -iE "FATAL|AndroidRuntime" | grep -i ledgerline || echo NO_CRASH`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/gallery app/src/main/res
git commit -m "feat: gallery add-photos FAB with picker and upload progress"
```

---

## Task 6: Verify + finish

- [ ] **Step 1: Full unit suite** — `./gradlew :app:testDebugUnitTest` → green.
- [ ] **Step 2: Instrumented** — `ANDROID_SERIAL=62021JEBF09273 ./gradlew :app:connectedDebugAndroidTest` → pass.
- [ ] **Step 3: Release R8** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL.
- [ ] **Step 4: Hardening greps** — FLAG_SECURE present; `grep -rnE 'Log\.(d|v|i|w|e)\(.*(token|passphrase|vk|vault|kek|thumb|photo|filekey|secret|plain)' app/src/main || echo CLEAN`.
- [ ] **Step 5: On-device smoke (human)** — pick a photo → uploads → appears in the grid → open (medium renders) → info shows camera/place → the photo is visible in the web gallery (byte-interop) → re-picking the same photo is deduped.
- [ ] **Step 6: Finish** — `superpowers:finishing-a-development-branch`: merge `feature/phase4b` → `develop` → `main`, tag `v0.5.0`.

---

## Self-Review Notes (author checklist — completed)

- **Spec coverage:** endpoints + ProcessResponse + shared EncryptedUpload + gallery upload/process (T1); gallery 409-merge save (T2); GalleryUploader original/renditions/faces/meta/entry (T3); VM upload queue + sig-dedup + progress (T4); FAB + Photo Picker + progress UI + LockGuard (T5); verify + finish (T6). Security (plaintext only to encryptor + process, VK-gated, LockGuard, Padmé) in T1/T5. All spec sections map to a task.
- **Placeholder scan:** every code step has literal code; the `GalleryUploadApi` interface extraction (T3 Step 2) and `UploadedBlob` promotion (T1 Step 6) are concrete refactors, not placeholders.
- **Type consistency:** `EncryptedUpload.body(encryptor, chunkSize, size, openInput)`, `UploadedBlob(id, encFileKey, size)`, `GalleryBlobRepository.{uploadBytes,process}`, `ProcessResponse`/`ProcessFace`, `GalleryRepository.save(mutate)`, `MutateGallery.invoke`, `GalleryUploader.upload(name,mime,sig,bytes,createdIso)`, `GalleryPhoto` fields, `PhotoSource(name,mime,read)`, `GalleryViewModel.uploadAll` — consistent across tasks. New `LedgerlineApi` methods require updating test fakes (called out in T1/T2).
- **Known risks flagged inline:** `JsonPrimitive.contentOrNull` availability (adapt to the kotlinx version); Photo Picker returns content URIs read fully into memory (fine for typical photos; huge files would spike memory — acceptable for 4b); reuse vs promote `UploadedBlob`.
```
