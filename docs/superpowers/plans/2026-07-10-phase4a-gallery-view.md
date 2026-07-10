# Ledgerline Android — Phase 4a Implementation Plan (Gallery view, read-only)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After unlock, load + decrypt the gallery index and show all photos as a lazy thumbnail grid with a zoomable full-photo viewer; restructure the bottom nav to Files · Gallery · Todos · Notes (Bookmarks → ⋮ overflow).

**Architecture:** A `GalleryRepository` loads `/gallery/store` and decrypts it (reusing `openManifest`). A shared `BlobDownloader` (extracted from Phase 3) frame-decrypts any `raw/{blob}`; `GalleryBlobRepository` uses it for thumbnails/renditions. A `GalleryViewModel` drives a `LazyVerticalGrid` with an in-memory LRU `ThumbCache`; a `PhotoViewerScreen` shows the medium rendition with zoom/pan. Everything decrypted stays in memory and is wiped on lock.

**Tech Stack:** Kotlin, Compose (Material 3, `LazyVerticalGrid`), Hilt, Retrofit/OkHttp, lazysodium (reused). Reuses all Phase 1–3 infra.

**Reference:** Spec `docs/superpowers/specs/2026-07-10-phase4a-gallery-view-design.md`. Ground truth `~/Entwicklung/ledgerline/resources/js/app.js` (`vaultGallery`). Blob format identical to Phase 3.

**Conventions:** English only. Conventional Commits. Branch `feature/phase4a` (already created). Physical device `62021JEBF09273` (`ANDROID_SERIAL=...`). JVM tests `./gradlew :app:testDebugUnitTest`. Keep FLAG_SECURE + hardening. Commit-message hook blocks AI trailers — plain messages only.

---

## File Structure

```
data/remote/LedgerlineApi.kt        (+galleryStore, galleryRaw, galleryUsage)
domain/model/Gallery.kt             (tolerant models + Gallery wrapper)
core/GalleryCache.kt                (new @Singleton StateFlow<Gallery?>)
core/ThumbCache.kt                  (new @Singleton bounded LRU photoId->Bitmap)
data/BlobDownloader.kt              (shared: rawBytes+encFileKey+vk -> plaintext)
data/GalleryRepository.kt           (load /gallery/store + decrypt)
data/GalleryBlobRepository.kt       (downloadThumb/downloadRendition) + GalleryBlobs seam
domain/usecase/LoadGallery.kt       (seam) + data/LoadGalleryImpl.kt
ui/gallery/GalleryViewModel.kt, GalleryScreen.kt, PhotoViewerScreen.kt
ui/workspace/WorkspaceScaffold.kt   (nav restructure)
di/WorkspaceModule.kt               (bind LoadGallery, GalleryBlobs)
MainActivity.kt                     (clear GalleryCache + ThumbCache on wipe)
res/values*/strings.xml             (+gallery strings)
```

---

## Task 1: Gallery endpoints, tolerant models, repository, cache

**Files:** Modify `data/remote/LedgerlineApi.kt`. Create `domain/model/Gallery.kt`, `core/GalleryCache.kt`, `data/GalleryRepository.kt`, `domain/usecase/LoadGallery.kt`, `data/LoadGalleryImpl.kt`. Modify `di/WorkspaceModule.kt`. Test `app/src/test/java/de/ledgerline/app/domain/model/GalleryParsingTest.kt`.

- [ ] **Step 1: Add endpoints to `LedgerlineApi.kt`**

```kotlin
    @GET("api/v1/gallery/store")
    suspend fun galleryStore(): Response<de.ledgerline.app.data.remote.dto.StoreResponse>

    @GET("api/v1/gallery/raw/{blob}")
    @retrofit2.http.Streaming
    suspend fun galleryRaw(@retrofit2.http.Path("blob") blob: String): Response<okhttp3.ResponseBody>

    @GET("api/v1/gallery/usage")
    suspend fun galleryUsage(): Response<de.ledgerline.app.data.remote.dto.UsageResponse>
```
Update any test `LedgerlineApi` fakes with `throw NotImplementedError()` overrides for these three (search test sources for `: LedgerlineApi`).

- [ ] **Step 2: Create `domain/model/Gallery.kt`** (tolerant, all fields defaulted)

```kotlin
package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GalleryPhoto(
    val id: String = "",
    val media_type: String = "image",
    val originalRef: String? = null, val originalKey: String? = null,
    val thumbRef: String? = null, val thumbKey: String? = null,
    val mediumRef: String? = null, val mediumKey: String? = null,
    val motionRef: String? = null, val motionKey: String? = null,
    val metaRef: String? = null, val metaKey: String? = null,
    val faceCropRefs: List<String> = emptyList(),
    val sig: String? = null,
    val lat: Double? = null, val lng: Double? = null,
    val width: Int? = null, val height: Int? = null, val duration: Double? = null,
    val created: String? = null, val trashed: Boolean = false,
)

@Serializable
data class GalleryAlbum(
    val id: String = "", val name: String = "", val photoIds: List<String> = emptyList(),
    val cover: String? = null, val created: String? = null,
)

@Serializable
data class GalleryPerson(
    val id: String = "", val name: String = "", val hidden: Boolean = false,
    val centroid: List<Double> = emptyList(),
)

@Serializable
data class GalleryManifest(
    val v: Int = 1,
    val photos: List<GalleryPhoto> = emptyList(),
    val albums: List<GalleryAlbum> = emptyList(),
    val people: List<GalleryPerson> = emptyList(),
)

/** Decrypted gallery index + server version (for later 4b writes). */
data class Gallery(val manifest: GalleryManifest, val version: Int)
```

- [ ] **Step 3: Failing parsing test**

`app/src/test/java/de/ledgerline/app/domain/model/GalleryParsingTest.kt`:
```kotlin
package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_photos_with_unknown_and_missing_fields() {
        val text = """
          {"v":1,"gadget":true,
           "photos":[
             {"id":"p1","media_type":"image","thumbRef":"t1","thumbKey":"{}","created":"2026-01-02T00:00:00Z","extra":9},
             {"id":"p2","media_type":"video","trashed":true}
           ],
           "albums":[{"id":"a1","name":"Trip","photoIds":["p1"]}]}
        """.trimIndent()
        val m = json.decodeFromString<GalleryManifest>(text)
        assertEquals(2, m.photos.size)
        assertEquals("t1", m.photos[0].thumbRef)
        assertTrue(m.photos[1].trashed)
        assertEquals("Trip", m.albums[0].name)
        assertEquals(0, m.people.size)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*GalleryParsingTest*"` → PASS after Step 2.

- [ ] **Step 4: Create `core/GalleryCache.kt`**

```kotlin
package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Gallery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryCache @Inject constructor() {
    private val _value = MutableStateFlow<Gallery?>(null)
    val value: StateFlow<Gallery?> = _value
    fun set(g: Gallery) { _value.value = g }
    fun clear() { _value.value = null }
}
```

- [ ] **Step 5: Create `data/GalleryRepository.kt`** (mirror `WorkspaceRepository.load`)

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.Session
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryRepository private constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder, vaultKeyHolder: VaultKeyHolder, crypto: Crypto) :
        this(sessionHolder, vaultKeyHolder, crypto, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Outcome<Gallery> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        return try {
            val res = apiProvider(session).galleryStore()
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> Outcome.Err(ErrorKind.NETWORK)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        json.decodeFromString<GalleryManifest>(plain)
                    } ?: GalleryManifest()
                    Outcome.Ok(Gallery(manifest, body.version))
                }
            }
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }
}
```

- [ ] **Step 6: `LoadGallery` seam + impl + DI bind**

`domain/usecase/LoadGallery.kt`:
```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery

interface LoadGallery { suspend fun invoke(): Outcome<Gallery> }
```
`data/LoadGalleryImpl.kt`:
```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.usecase.LoadGallery
import javax.inject.Inject

class LoadGalleryImpl @Inject constructor(
    private val repo: GalleryRepository,
    private val cache: GalleryCache,
) : LoadGallery {
    override suspend fun invoke(): Outcome<Gallery> {
        val res = repo.load()
        if (res is Outcome.Ok) cache.set(res.value)
        return res
    }
}
```
In `di/WorkspaceModule.kt`: `@Binds abstract fun bindLoadGallery(impl: LoadGalleryImpl): LoadGallery`.

- [ ] **Step 7: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app app/src/test
git commit -m "feat: add gallery store endpoints, tolerant models, repository and cache"
```

---

## Task 2: Shared `BlobDownloader` + `GalleryBlobRepository`

**Files:** Create `data/BlobDownloader.kt`, `data/GalleryBlobRepository.kt`, `domain/usecase/GalleryBlobs.kt`. Modify `data/FileBlobRepository.kt` (use the shared helper), `di/WorkspaceModule.kt`. Test `app/src/test/java/de/ledgerline/app/data/BlobDownloaderTest.kt`.

- [ ] **Step 1: Create `data/BlobDownloader.kt`** (pure decrypt of already-downloaded bytes)

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto

/** Frame-decrypts a downloaded blob (secretstream) into plaintext bytes.
 *  Mirrors Phase-3 streamDecrypted; stops at TAG_FINAL, ignoring the Padmé tail. */
object BlobDownloader {
    fun decrypt(cipherBytes: ByteArray, encFileKey: String, vk: ByteArray, crypto: Crypto): ByteArray {
        val dec = crypto.contentDecryptor(encFileKey, vk)
        dec.start(cipherBytes.copyOfRange(0, dec.headerBytes))
        val out = java.io.ByteArrayOutputStream()
        var off = dec.headerBytes
        while (off < cipherBytes.size) {
            val len = crypto.readU32le(cipherBytes, off); off += 4
            if (len <= 0 || off + len > cipherBytes.size) break   // Padmé tail
            val (msg, final) = dec.decryptFrame(cipherBytes.copyOfRange(off, off + len)); off += len
            out.write(msg)
            if (final) break
        }
        return out.toByteArray()
    }
}
```

- [ ] **Step 2: Refactor `FileBlobRepository.streamDecrypted`** to delegate the frame loop to `BlobDownloader.decrypt` (download the bytes, then `BlobDownloader.decrypt(bytes, encFileKey, vk, crypto)`). Keep all public method signatures + behavior identical; run the existing `DeleteThrottleTest` + unit suite to confirm no regression.

- [ ] **Step 3: `GalleryBlobs` seam + `GalleryBlobRepository`**

`domain/usecase/GalleryBlobs.kt`:
```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome

interface GalleryBlobs {
    /** Download + decrypt a gallery blob (thumb/medium/original) to bytes. */
    suspend fun download(ref: String, key: String): Outcome<ByteArray>
}
```
`data/GalleryBlobRepository.kt`:
```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.GalleryBlobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryBlobRepository private constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) : GalleryBlobs {
    @Inject constructor(sessionHolder: SessionHolder, vaultKeyHolder: VaultKeyHolder, crypto: Crypto) :
        this(sessionHolder, vaultKeyHolder, crypto, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    override suspend fun download(ref: String, key: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        try {
            val res = apiProvider(session).galleryRaw(ref)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            val bytes = res.body()!!.bytes()
            Outcome.Ok(BlobDownloader.decrypt(bytes, key, vk, crypto))
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
    }
}
```
Bind in `di/WorkspaceModule.kt`: `@Binds abstract fun bindGalleryBlobs(impl: GalleryBlobRepository): GalleryBlobs`.

- [ ] **Step 4: `BlobDownloaderTest`** (unit — round-trip using `SodiumCrypto`? No: SodiumCrypto loads native lib; JVM can't. Instead make this an instrumented test OR a pure fake-crypto test.) Use a small fake `Crypto` whose `contentDecryptor` reverses a trivial framing, proving `BlobDownloader.decrypt` walks frames + stops on final. Keep it JVM:
```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BlobDownloaderTest {
    // Fake crypto: header = 2 bytes; each frame's "cipher" == plaintext (no MAC); final flagged by a sentinel byte.
    private val fake = object : Crypto {
        override val contentChunkSize = 8
        override fun readU32le(bytes: ByteArray, off: Int) =
            (bytes[off].toInt() and 0xff) or ((bytes[off+1].toInt() and 0xff) shl 8) or ((bytes[off+2].toInt() and 0xff) shl 16) or ((bytes[off+3].toInt() and 0xff) shl 24)
        override fun u32le(n: Int) = byteArrayOf((n and 0xff).toByte(), ((n ushr 8) and 0xff).toByte(), ((n ushr 16) and 0xff).toByte(), ((n ushr 24) and 0xff).toByte())
        override fun contentDecryptor(encFileKey: String, vk: ByteArray) = object : Crypto.ContentDecryptor {
            override val headerBytes = 2
            override fun start(header: ByteArray) {}
            override fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean> {
                val isFinal = frame.last() == 1.toByte()
                return frame.copyOf(frame.size - 1) to isFinal
            }
        }
        // unused members:
        override fun deriveKek(p: ByteArray, s: ByteArray, o: Long, m: Long) = ByteArray(0)
        override fun secretBoxOpen(c: ByteArray, n: ByteArray, k: ByteArray): ByteArray? = null
        override fun genericHash32(i: ByteArray) = ByteArray(0)
        override fun b64decode(s: String) = ByteArray(0); override fun b64encode(b: ByteArray) = ""
        override fun fromHex(s: String) = ByteArray(0)
        override fun openManifest(ciphertext: String, vk: ByteArray): String? = null
        override fun sealManifest(json: String, vk: ByteArray) = ""
        override fun newContentEncryptor(vk: ByteArray) = throw NotImplementedError()
    }

    @Test fun walks_frames_and_stops_on_final() {
        // blob = header(2) ++ frame1[u32len]["AB"+0] ++ frame2[u32len]["C"+1(final)] ++ garbage padme tail
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(9, 9))                         // header
        val f1 = "AB".toByteArray() + byteArrayOf(0)         // not final
        out.write(fake.u32le(f1.size)); out.write(f1)
        val f2 = "C".toByteArray() + byteArrayOf(1)          // final
        out.write(fake.u32le(f2.size)); out.write(f2)
        out.write(byteArrayOf(7, 7, 7))                      // padme tail (ignored)
        val plain = BlobDownloader.decrypt(out.toByteArray(), "{}", ByteArray(32), fake)
        assertArrayEquals("ABC".toByteArray(), plain)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*BlobDownloaderTest*"` → PASS.

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app app/src/test
git commit -m "feat: shared blob downloader and gallery blob repository"
```

---

## Task 3: Nav restructure + ThumbCache + cache-clear on lock

**Files:** Modify `ui/workspace/WorkspaceScaffold.kt`, `MainActivity.kt`. Create `core/ThumbCache.kt`. Add strings.

- [ ] **Step 1: Create `core/ThumbCache.kt`** (bounded in-memory LRU)

```kotlin
package de.ledgerline.app.core

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory LRU of decrypted thumbnails (photoId -> Bitmap). Never persisted; cleared on lock. */
@Singleton
class ThumbCache @Inject constructor() {
    private val max = 250
    private val map = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > max
    }
    @Synchronized fun get(id: String): Bitmap? = map[id]
    @Synchronized fun put(id: String, bmp: Bitmap) { map[id] = bmp }
    @Synchronized fun clear() { map.clear() }
}
```

- [ ] **Step 2: Clear gallery + thumb caches on wipe (`MainActivity.kt`)**

Inject `GalleryCache` + `ThumbCache`; in both wipe paths (`onStop` and the idle branch of `onResume`) add `galleryCache.clear(); thumbCache.clear()` alongside the existing `workspaceCache.clear()`.

- [ ] **Step 3: Nav restructure in `WorkspaceScaffold.kt`**

Change the primary tab list to **Files, Gallery, Todos, Notes** and move **Bookmarks** into the ⋮ overflow (next to Settings). Concretely:
- `tabs = listOf(Tab(R.string.tab_files, Icons.Outlined.Folder), Tab(R.string.tab_gallery, Icons.Outlined.PhotoLibrary), Tab(R.string.tab_todos, Icons.Outlined.CheckCircle), Tab(R.string.tab_notes, Icons.Outlined.Description))` (import `Icons.Outlined.PhotoLibrary`).
- Content `when(selected)`: `0 -> FilesScreen`, `1 -> GalleryScreen`, `2 -> TodosScreen`, `3 -> NotesScreen`.
- The ⋮ `DropdownMenu`: keep the existing "Settings" item; add a "Bookmarks" item above it that sets a `showBookmarks` state (mirror the `showSettings` full-screen pattern) rendering `BookmarksScreen(Modifier.padding(innerPadding))` with the top bar showing the Bookmarks title + a back arrow that clears `showBookmarks`. When `showBookmarks` or `showSettings` is active, that full-screen content replaces the tab content (as Settings already does).
- Selecting any bottom tab clears `showSettings`/`showBookmarks`.

- [ ] **Step 4: Add strings (en+de)** — `tab_gallery` (`Gallery`/`Galerie`), and the gallery UI strings used later: `gallery_empty` (`No photos yet.`/`Noch keine Fotos.`), `gallery_error` (`Couldn't load the gallery.`/`Galerie konnte nicht geladen werden.`), `gallery_usage` (`%1$s used`/`%1$s belegt`), `photo_original` (`View original`/`Original anzeigen`), `photo_video_soon` (`Video playback comes later.`/`Videowiedergabe kommt später.`), `menu_bookmarks` (`Bookmarks`/`Lesezeichen`).

- [ ] **Step 5: Create a temporary `GalleryScreen` stub** so the scaffold compiles (replaced in Task 5):
```kotlin
package de.ledgerline.app.ui.gallery
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
@Composable fun GalleryScreen(modifier: Modifier = Modifier) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Gallery") } }
```

- [ ] **Step 6: Build + commit**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/de/ledgerline/app app/src/main/res
git commit -m "feat: gallery nav tab, thumb cache, cache-clear on lock (bookmarks to overflow)"
```

---

## Task 4: GalleryViewModel

**Files:** Rewrite `ui/gallery/GalleryScreen.kt` stub package remains; create `ui/gallery/GalleryViewModel.kt`. Test `app/src/test/java/de/ledgerline/app/ui/gallery/GalleryViewModelTest.kt`.

- [ ] **Step 1: Failing VM test** (sorted desc, trashed hidden)

```kotlin
package de.ledgerline.app.ui.gallery

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.LoadGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun gallery() = Gallery(GalleryManifest(photos = listOf(
        GalleryPhoto(id = "a", thumbRef = "t", thumbKey = "{}", created = "2026-01-01T00:00:00Z"),
        GalleryPhoto(id = "b", thumbRef = "t", thumbKey = "{}", created = "2026-02-01T00:00:00Z"),
        GalleryPhoto(id = "c", trashed = true, created = "2026-03-01T00:00:00Z"),
    )), version = 4)

    @Test fun newest_first_trashed_hidden() = runTest {
        val cache = GalleryCache()
        val load = object : LoadGallery { override suspend fun invoke(): Outcome<Gallery> { cache.set(gallery()); return Outcome.Ok(gallery()) } }
        val vm = GalleryViewModel(load, cache, blobs = FakeBlobs())
        vm.refresh()
        assertEquals(listOf("b", "a"), vm.state.value.photos.map { it.id })
    }
}
```
Add a `FakeBlobs : GalleryBlobs` returning `Outcome.Err` (thumbnails aren't asserted here).

- [ ] **Step 2: Implement `ui/gallery/GalleryViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.LoadGallery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val photos: List<GalleryPhoto> = emptyList(),
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val load: LoadGallery,
    private val cache: GalleryCache,
    private val blobs: GalleryBlobs,
    private val thumbs: ThumbCache = ThumbCache(),   // Hilt provides the singleton; default only for tests
) : ViewModel() {
    private val _state = MutableStateFlow(GalleryUi(loading = true))
    val state: StateFlow<GalleryUi> = _state

    init {
        viewModelScope.launch {
            cache.value.collect { g -> if (g != null) recompute() else _state.value = GalleryUi(loading = true) }
        }
        if (cache.value.value == null) refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) _state.value = _state.value.copy(loading = false, error = true)
    }

    /** Returns a cached thumbnail bitmap or downloads+decodes it (cached). Null on failure. */
    suspend fun thumb(photo: GalleryPhoto): Bitmap? {
        thumbs.get(photo.id)?.let { return it }
        val ref = photo.thumbRef ?: return null
        val key = photo.thumbKey ?: return null
        return when (val r = blobs.download(ref, key)) {
            is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size)?.also { thumbs.put(photo.id, it) }
            is Outcome.Err -> null
        }
    }

    fun photoById(id: String) = cache.value.value?.manifest?.photos?.firstOrNull { it.id == id }

    private fun recompute() {
        val photos = cache.value.value?.manifest?.photos.orEmpty()
            .filter { !it.trashed }
            .sortedByDescending { it.created ?: "" }
        _state.value = GalleryUi(false, false, photos)
    }
}
```
Note: for the unit test, pass a real `ThumbCache()`; in production Hilt injects the singleton. If Hilt complains about the defaulted param, drop the default and inject `ThumbCache` normally (then the test constructs `ThumbCache()` explicitly).
Run: `./gradlew :app:testDebugUnitTest --tests "*GalleryViewModelTest*"` → PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/gallery app/src/test
git commit -m "feat: gallery viewmodel with lazy thumbnail loading"
```

---

## Task 5: Gallery grid + photo viewer UI

**Files:** Rewrite `ui/gallery/GalleryScreen.kt`. Create `ui/gallery/PhotoViewerScreen.kt`.

- [ ] **Step 1: `GalleryScreen.kt`** — grid with lazy thumbnails

```kotlin
package de.ledgerline.app.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(modifier: Modifier = Modifier, vm: GalleryViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }

    val current = openId
    if (current != null) {
        val photo = vm.photoById(current)
        if (photo != null) { PhotoViewerScreen(photo, vm, onBack = { openId = null }, modifier = modifier); return }
    }
    when {
        ui.loading && ui.photos.isEmpty() -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.gallery_error), onRetry = { vm.refresh() }, modifier)
        ui.photos.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { CenteredMessage(stringResource(R.string.gallery_empty)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 116.dp), modifier = Modifier.fillMaxSize()) {
                items(ui.photos, key = { it.id }) { photo -> ThumbCell(photo, vm) { openId = photo.id } }
            }
        }
    }
}

@Composable
private fun ThumbCell(photo: GalleryPhoto, vm: GalleryViewModel, onClick: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) { value = vm.thumb(photo) }
    Box(Modifier.padding(1.dp).aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() }) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
        if (photo.media_type == "video") Text("▶", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurface)
    }
}
```

- [ ] **Step 2: `PhotoViewerScreen.kt`** — medium rendition, zoom/pan

```kotlin
package de.ledgerline.app.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import de.ledgerline.app.R
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.GalleryPhoto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(photo: GalleryPhoto, vm: GalleryViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }; var oy by remember { mutableFloatStateOf(0f) }
    // Load the medium rendition (fallback to thumb) into memory.
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) {
        val ref = photo.mediumRef ?: photo.thumbRef; val key = photo.mediumKey ?: photo.thumbKey
        value = if (ref != null && key != null) when (val r = vm.downloadBytes(ref, key)) {
            is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size); is Outcome.Err -> null
        } else null
    }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(photo.created?.take(10) ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            val b = bmp
            when {
                b != null -> Image(
                    b.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy)
                        .pointerInput(Unit) { detectTransformGestures { _, panChange, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); ox += panChange.x; oy += panChange.y } },
                )
                photo.media_type == "video" -> Text(stringResource(R.string.photo_video_soon), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
```
Add `suspend fun downloadBytes(ref, key) = blobs.download(ref, key)` to `GalleryViewModel` (exposes the medium/original fetch).

- [ ] **Step 3: Build + install + on-device smoke**

Run: `./gradlew :app:assembleDebug`. Install on `62021JEBF09273`, launch, `adb -s 62021JEBF09273 logcat -d | grep -iE "FATAL|AndroidRuntime" | grep -i ledgerline || echo NO_CRASH`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/gallery
git commit -m "feat: gallery grid and zoomable photo viewer"
```

---

## Task 6: Usage line, full verification, finish

- [ ] **Step 1: Gallery usage** — add a `GalleryUsage` fetch (reuse the `WorkspaceRepository.filesUsage` pattern with `galleryUsage()`), expose `usage` on `GalleryViewModel`, render `"X used"` (via `humanSize`; if quota>0 show `X of Y`) as a top row in `GalleryScreen` (mirror the Files usage row).
- [ ] **Step 2: Full unit suite** — `./gradlew :app:testDebugUnitTest` → all green.
- [ ] **Step 3: Instrumented** — `ANDROID_SERIAL=62021JEBF09273 ./gradlew :app:connectedDebugAndroidTest` → all pass (unchanged crypto/keystore).
- [ ] **Step 4: Release R8** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL.
- [ ] **Step 5: Hardening greps** — `grep -q FLAG_SECURE ... MainActivity.kt`; `grep -rnE 'Log\.(d|v|i|w|e)\(.*(token|passphrase|vk|vault|kek|thumb|photo|filekey)' app/src/main || echo CLEAN`.
- [ ] **Step 6: On-device smoke (human)** — unlock, open the Gallery tab → real photos load as a grid, open one (medium renders + pinch-zoom works), pull-to-refresh; confirm Bookmarks is reachable via ⋮.
- [ ] **Step 7: Finish** — invoke `superpowers:finishing-a-development-branch`: merge `feature/phase4a` → `develop` → `main`, tag `v0.4.0`.

---

## Self-Review Notes (author checklist — completed)

- **Spec coverage:** gallery endpoints + tolerant models + repo + cache (T1); shared BlobDownloader + gallery blob repo (T2); nav restructure Files/Gallery/Todos/Notes + Bookmarks→overflow + ThumbCache + cache-clear-on-lock (T3); GalleryViewModel lazy thumbs (T4); grid + zoom viewer (T5); usage + verify + finish (T6). Security (in-memory only, wipe on lock) in T3/T5. All spec sections map to a task.
- **Placeholder scan:** every code step has literal code; the Task-3 `GalleryScreen` stub is explicitly replaced in Task 5.
- **Type consistency:** `Gallery(manifest, version)`, `GalleryManifest.photos`, `GalleryPhoto.{thumbRef,thumbKey,mediumRef,mediumKey,media_type,trashed,created}`, `GalleryCache/ThumbCache` methods, `LoadGallery.invoke`, `GalleryBlobs.download(ref,key)`, `BlobDownloader.decrypt(bytes,encFileKey,vk,crypto)`, `GalleryViewModel.{thumb,downloadBytes,photoById,refresh}` — consistent across tasks. New `LedgerlineApi` methods require updating test API fakes (called out in T1).
- **Known risks flagged inline:** `Icons.Outlined.PhotoLibrary` from material-icons-extended (present); Hilt vs defaulted `ThumbCache` param in the VM (drop default if Hilt objects); `galleryRaw` buffers full ciphertext (same Phase-3 simplification, fine for thumbs/medium).
```
