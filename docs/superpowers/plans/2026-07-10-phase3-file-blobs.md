# Ledgerline Android — Phase 3 Implementation Plan (File Blobs: view / upload / writes)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Files tab fully usable — download+decrypt+view file content, encrypt+upload new files, and write the workspace manifest back (create/rename/delete folders and files) with 409-merge, all byte-compatible with `vault.js`.

**Architecture:** Add streaming secretstream content crypto + `sealManifest` + Padmé to the existing `Crypto` layer. A `FileBlobRepository` streams uploads/downloads (constant memory). `WorkspaceRepository.save(mutate)` performs optimistic writes with 409 reload-and-replay. The Phase-2 `FilesViewModel`/`FilesScreen` gain actions; a viewer renders images/text from in-memory bytes; export streams straight to a SAF `Uri` (no plaintext temp).

**Tech Stack:** Kotlin, Compose (Material 3), Hilt, Retrofit/OkHttp (streaming), lazysodium (secretstream XChaCha20-Poly1305), Storage Access Framework. Reuses all Phase 1–2 infra.

**Reference:** Spec `docs/superpowers/specs/2026-07-10-phase3-file-blobs-design.md`. Crypto ground truth `~/Entwicklung/ledgerline/resources/js/vault.js` (`encryptContent`, `decryptFile`, `newContentEncryptor`, `beginDecrypt`, `sealManifest`, `padmeSize`, `ciphertextSize`). Upload flow `~/Entwicklung/ledgerline/resources/js/app.js` (`vaultFiles`).

**Conventions:** English only. Conventional Commits. Branch `feature/phase3` off `develop`. Physical device `62021JEBF09273` (target with `ANDROID_SERIAL=62021JEBF09273`). JVM tests `./gradlew :app:testDebugUnitTest`; instrumented `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`. Keep FLAG_SECURE + all hardening intact. A project pre-commit hook blocks any AI-attribution trailer — commit with the plain message only.

---

## Preflight

- [ ] `cd /Users/malte.kiefer/Entwicklung/ledgerline-android && git checkout develop && git checkout -b feature/phase3`

---

## File Structure

```
core/crypto/Crypto.kt            (+sealManifest, +newContentEncryptor, +decryptContent)
core/crypto/SodiumCrypto.kt      (implementations)
core/crypto/Padme.kt             (new — padmeSize/padBytesCount)
data/remote/dto/FilesDtos.kt     (new — UploadResponse, StorePutRequest, UsageResponse)
data/remote/LedgerlineApi.kt     (+rawFile, uploadFile, deleteBlob, putStore, filesUsage)
data/WorkspaceRepository.kt      (+save(mutate) with 409 merge)
data/FileBlobRepository.kt       (new — download+decrypt, upload+encrypt+padme, deleteBlob throttled)
ui/workspace/files/FilesViewModel.kt   (+actions + FileAction state)
ui/workspace/files/FilesScreen.kt      (FAB, row actions, dialogs, viewer routing)
ui/workspace/files/FileViewerScreen.kt (new — image/text viewer)
ui/workspace/files/SafLaunchers.kt     (new — pick-to-upload + save-to-export helpers)
di/WorkspaceModule.kt            (bind FileBlobRepository if interfaced)
res/values/strings.xml, values-de/strings.xml (+file-action strings)
```

---

## Task 1: `sealManifest` + Padmé

**Files:** Modify `core/crypto/Crypto.kt`, `core/crypto/SodiumCrypto.kt`. Create `core/crypto/Padme.kt`. Tests `app/src/test/java/de/ledgerline/app/core/crypto/PadmeTest.kt`, `app/src/androidTest/java/de/ledgerline/app/core/crypto/SealManifestTest.kt`.

- [ ] **Step 1: Create `core/crypto/Padme.kt` + failing unit test**

`core/crypto/Padme.kt`:
```kotlin
package de.ledgerline.app.core.crypto

import kotlin.math.floor
import kotlin.math.ln

/** Padmé bucket size (vault.js padmeSize): rounds n up to hide its exact length. */
fun padmeSize(n: Long): Long {
    if (n < 2) return n
    val e = floor(ln(n.toDouble()) / ln(2.0)).toInt()          // floor(log2 n)
    val s = floor(ln(e.toDouble()) / ln(2.0)).toInt() + 1       // floor(log2 e)+1
    val bits = e - s
    if (bits <= 0) return n
    val mask = (1L shl bits) - 1
    return (n + mask) and mask.inv()
}

/** Number of random padding bytes to append to a blob of the given size. */
fun padByteCount(size: Long): Long = padmeSize(size) - size
```

`app/src/test/java/de/ledgerline/app/core/crypto/PadmeTest.kt`:
```kotlin
package de.ledgerline.app.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadmeTest {
    @Test fun small_values_unchanged() {
        assertEquals(0L, padmeSize(0)); assertEquals(1L, padmeSize(1))
    }
    @Test fun padme_never_shrinks_and_is_bounded() {
        for (n in longArrayOf(3, 100, 1000, 1024, 1_048_576, 5_000_000, 2_000_000_000)) {
            val p = padmeSize(n)
            assertTrue("padme($n)=$p must be >= n", p >= n)
            assertTrue("overhead <= ~12%", p <= n + n / 8 + 1)
        }
    }
    @Test fun pad_count_is_difference() {
        assertEquals(padmeSize(1000) - 1000, padByteCount(1000))
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*PadmeTest*"` → after implementation, PASS. (Run first to confirm red.)

- [ ] **Step 2: Add `sealManifest` to `Crypto.kt` interface**

```kotlin
    /** Inverse of openManifest: pad to a 4-KiB bucket and secretbox-seal to `{"c","n"}`. */
    fun sealManifest(json: String, vk: ByteArray): String
```

- [ ] **Step 3: Failing instrumented round-trip test**

`app/src/androidTest/java/de/ledgerline/app/core/crypto/SealManifestTest.kt`:
```kotlin
package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SealManifestTest {
    private val crypto = SodiumCrypto()

    @Test fun seal_then_open_roundtrips_with_padding() {
        val vk = ByteArray(32) { (it + 5).toByte() }
        val json = """{"v":1,"files":[{"id":"f1","name":"a.txt"}]}"""
        val sealed = crypto.sealManifest(json, vk)
        val opened = crypto.openManifest(sealed, vk)!!
        // openManifest returns the padded plaintext; the JSON prefix must match.
        assertTrue(opened.startsWith(json))
        assertTrue("padded to a 4-KiB bucket", opened.length % 4096 == 0)
        // The JSON is still parseable after trimming trailing whitespace.
        assertEquals('}', opened.trimEnd().last())
    }
}
```

- [ ] **Step 4: Implement `sealManifest` in `SodiumCrypto.kt`**

Add a helper that mirrors the existing `secretBoxSealForTest` but for arbitrary data, plus `sealManifest`:
```kotlin
    override fun sealManifest(json: String, vk: ByteArray): String {
        val bucket = 4096
        val target = ((json.length + 1 + bucket - 1) / bucket) * bucket   // ceil((len+1)/4096)*4096
        val padded = json + " ".repeat(target - json.length)
        val plain = padded.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(SecretBox.NONCEBYTES)                       // 24
        randomBytes(nonce)
        val cipher = ByteArray(plain.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, vk)) { "seal failed" }
        return lenientJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("c", kotlinx.serialization.json.JsonPrimitive(b64encode(cipher)))
                put("n", kotlinx.serialization.json.JsonPrimitive(b64encode(nonce)))
            },
        )
    }
```
Add a `randomBytes` helper if not present:
```kotlin
    private fun randomBytes(out: ByteArray) { ls.randombytes_buf(out, out.size) }
```
Note: verify lazysodium 5.1.0 exposes `randombytes_buf(byte[], int)` (Random interface) and `SecretBox.NONCEBYTES`/`MACBYTES`; adapt names if needed. The instrumented `SealManifestTest` + the existing `openManifest` prove correctness. String building for `{"c","n"}` may also be done with a manual `"""{"c":"...","n":"..."}"""` template to avoid serializer friction — either is fine as long as the test passes.

- [ ] **Step 5: Run tests → PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*PadmeTest*"` → PASS.
Run: `ANDROID_SERIAL=62021JEBF09273 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.ledgerline.app.core.crypto.SealManifestTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/crypto app/src/test app/src/androidTest
git commit -m "feat: add sealManifest and Padme padding primitives"
```

---

## Task 2: Streaming content cipher (secretstream)

**Files:** Modify `core/crypto/Crypto.kt`, `core/crypto/SodiumCrypto.kt`. Test `app/src/androidTest/java/de/ledgerline/app/core/crypto/ContentCipherTest.kt`.

Design: expose two streaming objects on `Crypto`. Because lazysodium's secretstream `push`/`pull` operate on whole messages, we process one 4-MiB plaintext chunk at a time and frame each with a `u32le` length prefix — exactly matching `vault.js`.

- [ ] **Step 1: Add to `Crypto.kt` interface**

```kotlin
    /** Streaming content encryptor with a fresh per-file key. */
    fun newContentEncryptor(vk: ByteArray): ContentEncryptor

    /** Streaming content decryptor; unwraps the per-file key with vk. */
    fun contentDecryptor(encFileKey: String, vk: ByteArray): ContentDecryptor

    interface ContentEncryptor {
        val header: ByteArray                       // 24 bytes, written first
        /** Encrypt one plaintext chunk → framed (u32le length + ciphertext). */
        fun encryptChunk(chunk: ByteArray, isLast: Boolean): ByteArray
        /** The wrapped per-file key as a JSON {"c","n"} string. */
        fun sealKey(): String
    }
    interface ContentDecryptor {
        val headerBytes: Int                        // 24
        fun start(header: ByteArray)
        /** Decrypt one ciphertext frame → (message, isFinal). */
        fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean>
    }

    /** Little-endian u32 helpers for the frame length prefix. */
    fun u32le(n: Int): ByteArray
    fun readU32le(bytes: ByteArray, off: Int): Int
```
Also expose `val contentChunkSize: Int` (= 4 * 1024 * 1024) on `Crypto` for callers.

- [ ] **Step 2: Failing instrumented round-trip test**

`app/src/androidTest/java/de/ledgerline/app/core/crypto/ContentCipherTest.kt`:
```kotlin
package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ContentCipherTest {
    private val crypto = SodiumCrypto()
    private val vk = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, String> {
        val enc = crypto.newContentEncryptor(vk)
        val out = ByteArrayOutputStream()
        out.write(enc.header)
        val chunk = crypto.contentChunkSize
        var off = 0
        do {
            val end = minOf(off + chunk, plain.size)
            val slice = plain.copyOfRange(off, end)
            val last = end >= plain.size
            out.write(enc.encryptChunk(slice, last))
            off = end
        } while (off < plain.size)
        if (plain.isEmpty()) { /* one final empty frame already written above? */ }
        return out.toByteArray() to enc.sealKey()
    }

    private fun decrypt(blob: ByteArray, encFileKey: String): ByteArray {
        val dec = crypto.contentDecryptor(encFileKey, vk)
        dec.start(blob.copyOfRange(0, dec.headerBytes))
        val out = ByteArrayOutputStream()
        var off = dec.headerBytes
        while (off < blob.size) {
            val len = crypto.readU32le(blob, off); off += 4
            val (msg, final) = dec.decryptFrame(blob.copyOfRange(off, off + len)); off += len
            out.write(msg)
            if (final) break
        }
        return out.toByteArray()
    }

    @Test fun roundtrips_multichunk() {
        val plain = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }  // spans 3 chunks
        val (blob, key) = encrypt(plain)
        assertArrayEquals(plain, decrypt(blob, key))
    }

    @Test fun roundtrips_small_and_empty() {
        val small = "hello vault".toByteArray()
        val (b1, k1) = encrypt(small)
        assertArrayEquals(small, decrypt(b1, k1))
        val (b2, k2) = encrypt(ByteArray(0))
        assertEquals(0, decrypt(b2, k2).size)
    }
}
```
Note: for the empty-input case, the encrypt loop must still emit exactly one final (empty) frame — implement the encrypt helper in the cipher so callers can pass an empty chunk with `isLast=true`. Adjust the test's encrypt helper if the chosen chunking API differs, but keep the assertions (empty and multi-chunk round-trip) intact.

- [ ] **Step 3: Implement in `SodiumCrypto.kt`**

```kotlin
    override val contentChunkSize: Int = 4 * 1024 * 1024

    override fun u32le(n: Int): ByteArray =
        byteArrayOf((n and 0xff).toByte(), ((n ushr 8) and 0xff).toByte(), ((n ushr 16) and 0xff).toByte(), ((n ushr 24) and 0xff).toByte())

    override fun readU32le(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8) or
        ((bytes[off + 2].toInt() and 0xff) shl 16) or ((bytes[off + 3].toInt() and 0xff) shl 24)

    override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor {
        val fk = ByteArray(SecretStream.KEYBYTES)
        ls.cryptoSecretStreamKeygen(fk)
        val state = SecretStream.State.ByReference()
        val header = ByteArray(SecretStream.HEADERBYTES)
        check(ls.cryptoSecretStreamInitPush(state, header, fk)) { "init_push failed" }
        return object : Crypto.ContentEncryptor {
            override val header = header
            override fun encryptChunk(chunk: ByteArray, isLast: Boolean): ByteArray {
                val cipher = ByteArray(chunk.size + SecretStream.ABYTES)
                val tag = if (isLast) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
                check(ls.cryptoSecretStreamPush(state, cipher, longArrayOf(0), chunk, chunk.size.toLong(), byteArrayOf(), 0, tag)) { "push failed" }
                return u32le(cipher.size) + cipher
            }
            override fun sealKey(): String {
                val nonce = ByteArray(SecretBox.NONCEBYTES); randomBytes(nonce)
                val wrapped = ByteArray(fk.size + SecretBox.MACBYTES)
                check(ls.cryptoSecretBoxEasy(wrapped, fk, fk.size.toLong(), nonce, vk)) { "wrap failed" }
                return """{"c":"${b64encode(wrapped)}","n":"${b64encode(nonce)}"}"""
            }
        }
    }

    override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor {
        val env = lenientJson.parseToJsonElement(encFileKey) as kotlinx.serialization.json.JsonObject
        val fk = secretBoxOpen(b64decode(env["c"]!!.jsonPrimitive.content), b64decode(env["n"]!!.jsonPrimitive.content), vk)
            ?: error("file key unwrap failed")
        val state = SecretStream.State.ByReference()
        return object : Crypto.ContentDecryptor {
            override val headerBytes = SecretStream.HEADERBYTES
            override fun start(header: ByteArray) { check(ls.cryptoSecretStreamInitPull(state, header, fk)) { "init_pull failed" } }
            override fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean> {
                val message = ByteArray(frame.size - SecretStream.ABYTES)
                val tag = ByteArray(1)
                check(ls.cryptoSecretStreamPull(state, message, longArrayOf(0), tag, frame, frame.size.toLong(), byteArrayOf(), 0)) { "pull failed" }
                return message to (tag[0] == SecretStream.TAG_FINAL)
            }
        }
    }
```
IMPORTANT: lazysodium 5.1.0's secretstream API (`SecretStream.State.ByReference`, method arities, `TAG_*` constant types) must be verified — inspect the resolved `com.goterl.lazysodium.interfaces.SecretStream` and `LazySodiumAndroid` methods and adapt argument order/types so the instrumented `ContentCipherTest` passes. The tag param may be a `byte` vs `byte[]`; the `push`/`pull` may return the tag differently. Keep the crypto semantics (XChaCha20-Poly1305 secretstream, u32le framing, secretbox-wrapped fk) identical to `vault.js`. The passing round-trip test is the acceptance criterion.

- [ ] **Step 4: Run instrumented test → PASS**

Run: `ANDROID_SERIAL=62021JEBF09273 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.ledgerline.app.core.crypto.ContentCipherTest`
Expected: PASS (multichunk + small + empty).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/crypto app/src/androidTest
git commit -m "feat: add streaming secretstream content cipher (byte-parity with vault.js)"
```

---

## Task 3: Networking — files endpoints + DTOs

**Files:** Create `data/remote/dto/FilesDtos.kt`. Modify `data/remote/LedgerlineApi.kt`. Test `app/src/test/java/de/ledgerline/app/data/remote/FilesApiTest.kt`.

- [ ] **Step 1: Create `data/remote/dto/FilesDtos.kt`**

```kotlin
package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class UploadResponse(val id: String)
@Serializable data class StorePutRequest(val ciphertext: String, val version: Int)
@Serializable data class UsageResponse(val used: Long = 0, val quota: Long = 0)
```

- [ ] **Step 2: Add endpoints to `LedgerlineApi.kt`**

Add imports (`okhttp3.MultipartBody`, `okhttp3.ResponseBody`, `retrofit2.http.*`) and:
```kotlin
    @GET("api/v1/files/raw/{blob}")
    @retrofit2.http.Streaming
    suspend fun rawFile(@retrofit2.http.Path("blob") blob: String): Response<okhttp3.ResponseBody>

    @retrofit2.http.Multipart
    @POST("api/v1/files/upload")
    suspend fun uploadFile(@retrofit2.http.Part file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse>

    @retrofit2.http.DELETE("api/v1/files/blob/{blob}")
    suspend fun deleteBlob(@retrofit2.http.Path("blob") blob: String): Response<Unit>

    @retrofit2.http.PUT("api/v1/store")
    suspend fun putStore(@Body body: de.ledgerline.app.data.remote.dto.StorePutRequest): Response<de.ledgerline.app.data.remote.dto.StoreResponse>

    @GET("api/v1/files/usage")
    suspend fun filesUsage(): Response<de.ledgerline.app.data.remote.dto.UsageResponse>
```

- [ ] **Step 3: Failing MockWebServer test for upload + putStore**

`app/src/test/java/de/ledgerline/app/data/remote/FilesApiTest.kt`:
```kotlin
package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.StorePutRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FilesApiTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api() = NetworkFactory.create(server.url("/").toString(), { "tok" }, null, allowCleartext = true)

    @Test fun upload_returns_id() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"blob-1"}""").addHeader("Content-Type", "application/json"))
        val part = MultipartBody.Part.createFormData("file", "a.bin", "cipher".toByteArray().toRequestBody("application/octet-stream".toMediaType()))
        val res = api().uploadFile(part)
        assertEquals("blob-1", res.body()!!.id)
    }

    @Test fun putStore_returns_new_version() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":8}""").addHeader("Content-Type", "application/json"))
        val res = api().putStore(StorePutRequest("ct", 7))
        assertEquals(8, res.body()!!.version)
    }

    @Test fun putStore_conflict_is_409() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        val res = api().putStore(StorePutRequest("ct", 1))
        assertEquals(409, res.code())
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*FilesApiTest*"` → PASS after Step 2. (Uses the existing `internal NetworkFactory.create(..., allowCleartext=true)` test overload.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data/remote app/src/test
git commit -m "feat: add files blob + store-write endpoints and DTOs"
```

---

## Task 4: `WorkspaceRepository.save(mutate)` with 409 merge

**Files:** Modify `data/WorkspaceRepository.kt`. Create `domain/usecase/MutateWorkspace.kt`. Test `app/src/test/java/de/ledgerline/app/data/WorkspaceSaveTest.kt`.

- [ ] **Step 1: Failing test (fake API, 409 then success)**

Because `WorkspaceRepository` builds its API internally via `NetworkFactory`, refactor it to depend on an injectable `apiProvider: (Session) -> LedgerlineApi` so tests can inject a fake. Define the seam + test:

`app/src/test/java/de/ledgerline/app/data/WorkspaceSaveTest.kt`:
```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.dto.*
import de.ledgerline.app.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WorkspaceSaveTest {
    // Fake crypto: sealManifest returns the raw json (tagged), openManifest strips the tag.
    private val fakeCrypto = object : Crypto {
        override fun sealManifest(json: String, vk: ByteArray) = "SEALED:$json"
        override fun openManifest(ciphertext: String, vk: ByteArray) = ciphertext.removePrefix("SEALED:")
        override fun deriveKek(p: ByteArray, s: ByteArray, o: Long, m: Long) = ByteArray(32)
        override fun secretBoxOpen(c: ByteArray, n: ByteArray, k: ByteArray) = ByteArray(0)
        override fun genericHash32(i: ByteArray) = ByteArray(32)
        override fun b64decode(s: String) = s.toByteArray(); override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
        override val contentChunkSize = 1; override fun u32le(n: Int) = ByteArray(4); override fun readU32le(b: ByteArray, o: Int) = 0
        override fun newContentEncryptor(vk: ByteArray) = throw NotImplementedError()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray) = throw NotImplementedError()
    }
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // Fake API: first PUT → 409, GET returns version 5 with one file, second PUT → 200 version 6.
    private class FakeApi(val manifestJson: String) : LedgerlineApi {
        var puts = 0
        override suspend fun store(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$manifestJson", 5))
        override suspend fun putStore(body: StorePutRequest): Response<StoreResponse> {
            puts++
            return if (puts == 1) Response.error(409, ResponseBody.create(null, ""))
                   else Response.success(StoreResponse(body.ciphertext, body.version + 1))
        }
        // unused endpoints:
        override suspend fun claimPair(b: PairClaimRequest) = throw NotImplementedError()
        override suspend fun pollPair(c: String) = throw NotImplementedError()
        override suspend fun vault() = throw NotImplementedError()
        override suspend fun rawFile(blob: String) = throw NotImplementedError()
        override suspend fun uploadFile(file: MultipartBody.Part) = throw NotImplementedError()
        override suspend fun deleteBlob(blob: String) = throw NotImplementedError()
        override suspend fun filesUsage() = throw NotImplementedError()
    }

    @Test fun save_merges_on_409_and_retries() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi("""{"v":1,"fileFolders":[{"id":"d1","name":"Docs"}]}""")
        val repo = WorkspaceRepository(sh, vh, fakeCrypto, apiProvider = { fakeApi })

        val result = repo.save { m -> m.copy(fileFolders = m.fileFolders + NamedFolder("d2", "New", null)) }
        assertTrue(result is Outcome.Ok)
        // Merge applied on the server's fresh manifest (which had Docs) → both folders present.
        val names = (result as Outcome.Ok).value.manifest.fileFolders.map { it.name }.toSet()
        assertEquals(setOf("Docs", "New"), names)
        assertEquals(2, fakeApi.puts)   // 409 then success
        assertEquals(6, result.value.version)
    }
}
```

- [ ] **Step 2: Refactor `WorkspaceRepository` for the injectable api provider + add `save`**

Change the constructor to add `private val apiProvider: (Session) -> LedgerlineApi = { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) }` and replace internal `NetworkFactory.create(...)` calls in `load()` with `apiProvider(session)`. Then add:
```kotlin
    private val jsonEncoder = kotlinx.serialization.json.Json { encodeDefaults = true }

    /**
     * Optimistic write: apply [mutate] to the current manifest, PUT it; on 409 reload
     * the server manifest, re-apply [mutate], and retry (bounded). Updates the cache.
     */
    suspend fun save(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        var version = cache.value.value?.version
        var base = cache.value.value?.manifest
        repeat(4) {
            if (base == null || version == null) {
                // Ensure we have a starting point.
                val res = api.store()
                if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                val body = res.body()!!
                base = body.ciphertext?.let { json.decodeFromString<WorkspaceManifest>(crypto.openManifest(it, vk)!!) } ?: WorkspaceManifest()
                version = body.version
            }
            val next = mutate(base!!)
            val ciphertext = crypto.sealManifest(jsonEncoder.encodeToString(WorkspaceManifest.serializer(), next), vk)
            val put = try { api.putStore(StorePutRequest(ciphertext, version!!)) } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
            when {
                put.isSuccessful -> {
                    val newVersion = put.body()?.version ?: (version!! + 1)
                    val ws = Workspace(next, newVersion)
                    cache.set(ws)
                    return Outcome.Ok(ws)
                }
                put.code() == 409 -> {
                    // Reload fresh, then loop to re-apply mutate on it.
                    val res = api.store()
                    if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                    val body = res.body()!!
                    base = body.ciphertext?.let { json.decodeFromString<WorkspaceManifest>(crypto.openManifest(it, vk)!!) } ?: WorkspaceManifest()
                    version = body.version
                }
                else -> return Outcome.Err(ErrorKind.HTTP)
            }
        }
        return Outcome.Err(ErrorKind.HTTP)   // gave up after retries
    }
```
Add the needed imports (`StorePutRequest`). `MutateWorkspace` seam file:

`domain/usecase/MutateWorkspace.kt`:
```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest

/** A workspace write expressed as a pure manifest mutation (409-merge-safe). */
interface MutateWorkspace {
    suspend fun invoke(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace>
}
```
And `data/MutateWorkspaceImpl.kt` delegating to `WorkspaceRepository.save`:
```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.MutateWorkspace
import javax.inject.Inject

class MutateWorkspaceImpl @Inject constructor(private val repo: WorkspaceRepository) : MutateWorkspace {
    override suspend fun invoke(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> = repo.save(mutate)
}
```
Bind in `di/WorkspaceModule.kt`: `@Binds abstract fun bindMutateWorkspace(impl: MutateWorkspaceImpl): MutateWorkspace`.

- [ ] **Step 3: Run test → PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkspaceSaveTest*"` → PASS. Keep the whole unit suite green (the added `Crypto` interface members mean any existing `Crypto` fakes — e.g. in `UnlockVaultTest`, `WorkspaceSaveTest` — must implement the new methods; update those fakes with `throw NotImplementedError()` stubs for the content methods).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data app/src/main/java/de/ledgerline/app/domain app/src/main/java/de/ledgerline/app/di app/src/test
git commit -m "feat: add optimistic workspace save with 409 merge-and-retry"
```

---

## Task 5: `FileBlobRepository` (download / upload / delete)

**Files:** Create `data/FileBlobRepository.kt`, `domain/usecase/{UploadFile,DownloadFile}.kt`. Modify `di/WorkspaceModule.kt`. Test `app/src/test/java/de/ledgerline/app/data/DeleteThrottleTest.kt`.

- [ ] **Step 1: Implement `data/FileBlobRepository.kt`**

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.padByteCount
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

data class UploadedBlob(val id: String, val encFileKey: String, val size: Long)

@Singleton
class FileBlobRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
) {
    private fun api(session: Session) = NetworkFactory.create(session.baseUrl, { session.token }, session.spkiPin)

    /** Stream-encrypt [input] (length [size]) with a fresh key + Padmé pad, upload, return the blob ref + wrapped key. */
    suspend fun upload(name: String, mime: String, size: Long, openInput: () -> InputStream): Outcome<UploadedBlob> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val enc = crypto.newContentEncryptor(vk)
        // framed size = header + total + chunks*(ABYTES+4); pad to Padmé bucket with random bytes.
        val chunks = if (size == 0L) 1L else (size + crypto.contentChunkSize - 1) / crypto.contentChunkSize
        val framed = 24L + size + chunks * (17L + 4L)
        val pad = padByteCount(framed)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.write(enc.header)
                openInput().use { ins ->
                    val buf = ByteArray(crypto.contentChunkSize)
                    var remaining = size
                    if (size == 0L) { sink.write(enc.encryptChunk(ByteArray(0), true)); }
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        var read = 0
                        while (read < want) { val r = ins.read(buf, read, want - read); if (r < 0) break; read += r }
                        val last = remaining - read <= 0
                        sink.write(enc.encryptChunk(buf.copyOf(read), last))
                        remaining -= read
                    }
                }
                if (pad > 0) {
                    val rnd = SecureRandom(); val block = ByteArray(64 * 1024); var left = pad
                    while (left > 0) { val n = minOf(block.size.toLong(), left).toInt(); rnd.nextBytes(block); sink.write(block, 0, n); left -= n }
                }
            }
        }
        try {
            val part = MultipartBody.Part.createFormData("file", name, body)
            val res = api(session).uploadFile(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), size))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /** Download + decrypt a blob fully into memory (for in-app viewing / small files). */
    suspend fun downloadToBytes(blob: String, encFileKey: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        streamDecrypted(blob, encFileKey) { produce ->
            val out = java.io.ByteArrayOutputStream()
            produce { chunk -> out.write(chunk) }
            out.toByteArray()
        }
    }

    /** Stream decrypt a blob, invoking [write] per plaintext chunk (for SAF export). */
    suspend fun downloadTo(blob: String, encFileKey: String, write: (ByteArray) -> Unit): Outcome<Unit> = withContext(Dispatchers.IO) {
        streamDecrypted(blob, encFileKey) { produce -> produce { chunk -> write(chunk) } }
    }

    private inline fun <T> streamDecrypted(blob: String, encFileKey: String, block: ((consume: (ByteArray) -> Unit) -> Unit) -> T): Outcome<T> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        return try {
            // NOTE: kept simple — buffer the response, then frame-decrypt. Large-file true streaming can refine later.
            val res = kotlinx.coroutines.runBlocking { api(session).rawFile(blob) }
            if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
            val bytes = res.body()!!.bytes()
            val dec = crypto.contentDecryptor(encFileKey, vk)
            dec.start(bytes.copyOfRange(0, dec.headerBytes))
            var off = dec.headerBytes
            val result = block { consume ->
                while (off < bytes.size) {
                    val len = crypto.readU32le(bytes, off); off += 4
                    if (len <= 0 || off + len > bytes.size) break   // reached Padmé tail
                    val (msg, final) = dec.decryptFrame(bytes.copyOfRange(off, off + len)); off += len
                    consume(msg)
                    if (final) break
                }
            }
            Outcome.Ok(result)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
    }

    /** Delete freed blobs with ≤4 concurrent lanes, honoring Retry-After on 429. */
    suspend fun deleteBlobs(blobs: List<String>) = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext
        val a = api(session)
        for (id in blobs) {
            var attempt = 0
            while (attempt < 3) {
                val res = try { a.deleteBlob(id) } catch (_: Exception) { break }
                if (res.code() == 429) {
                    val wait = res.headers()["Retry-After"]?.toLongOrNull()?.times(1000) ?: (1000L shl attempt)
                    delay(minOf(wait, 30_000)); attempt++
                } else break
            }
        }
    }
}
```
Note: the download path buffers the full ciphertext then frame-decrypts. That's acceptable for Phase 3 (viewing/exporting typical files); a fully-streamed `Source` refinement is a later optimization — flag it in a code comment. Verify `okio.BufferedSink.write(ByteArray)` and `res.body()!!.bytes()` usages against the OkHttp version.

- [ ] **Step 2: Use-case seams** `domain/usecase/UploadFile.kt` + `DownloadFile.kt` (thin wrappers so ViewModels can be faked) — optional but keep parity with the codebase pattern:
```kotlin
package de.ledgerline.app.domain.usecase
// UploadFile.kt
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.UploadedBlob
import java.io.InputStream
interface UploadFile { suspend fun invoke(name: String, mime: String, size: Long, open: () -> InputStream): Outcome<UploadedBlob> }
```
Bind an impl delegating to `FileBlobRepository.upload` in `WorkspaceModule` (mirror `LoadWorkspaceImpl`). Do the same for a `DownloadFile` interface delegating to `downloadToBytes`. (If this adds friction, ViewModels may depend on `FileBlobRepository` directly — acceptable; then skip the seams.)

- [ ] **Step 3: Delete-throttle unit test**

`app/src/test/java/de/ledgerline/app/data/DeleteThrottleTest.kt` — exercise `deleteBlobs` against MockWebServer returning `429` (with `Retry-After: 0`) then `200`, assert it eventually issues the successful delete. (Construct `FileBlobRepository` with a `SessionHolder` pointing at the MockWebServer URL; since `FileBlobRepository` builds its api via `NetworkFactory.create(...)` with pinning null and the server is http, use a session whose baseUrl is the MockWebServer url and rely on the `allowCleartext` path — if pinning blocks it, add an injectable api provider to `FileBlobRepository` mirroring Task 4 and inject the cleartext api in the test.)
```kotlin
package de.ledgerline.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DeleteThrottleTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun retries_on_429_then_succeeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        // Build repo with an injectable cleartext api provider (add the provider param to FileBlobRepository, default = NetworkFactory).
        val repo = FileBlobRepository.forTest(server.url("/").toString())   // add a test factory that uses allowCleartext api
        repo.deleteBlobs(listOf("blob-x"))
        assertEquals(2, server.requestCount)
    }
}
```
Add a small `internal companion` test factory / injectable api provider on `FileBlobRepository` (mirroring Task 4's `apiProvider`) so this test works over cleartext MockWebServer. Keep production behavior (pinned api) unchanged.

- [ ] **Step 4: Run test + build → green. Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data app/src/main/java/de/ledgerline/app/domain app/src/main/java/de/ledgerline/app/di app/src/test
git commit -m "feat: add file blob repository (streaming upload/download, throttled delete)"
```

---

## Task 6: FilesViewModel actions

**Files:** Modify `ui/workspace/files/FilesViewModel.kt`. Test extend `app/src/test/java/de/ledgerline/app/ui/workspace/files/FilesViewModelTest.kt`.

- [ ] **Step 1: Add actions to `FilesViewModel`**

Inject `MutateWorkspace` + `FileBlobRepository`. Add methods (each mutates via `MutateWorkspace.invoke`; the cache-flow collector from Phase 2 recomputes the list automatically):
```kotlin
    fun createFolder(name: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(fileFolders = m.fileFolders + NamedFolder(newId(), name, stack.last())) }
    }
    fun renameFolder(id: String, name: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(fileFolders = m.fileFolders.map { if (it.id == id) it.copy(name = name) else it }) }
    }
    fun renameFile(id: String, name: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(files = m.files.map { if (it.id == id) it.copy(name = name) else it }) }
    }
    fun deleteFile(file: FileEntry) = viewModelScope.launch {
        val res = mutate.invoke { m -> m.copy(files = m.files.filterNot { it.id == file.id }) }
        if (res is Outcome.Ok) blobRepo.deleteBlobs(listOf(file.blob))
    }
    fun deleteFolder(folderId: String) = viewModelScope.launch {
        val m0 = cache.value.value?.manifest ?: return@launch
        val subFolders = collectSubtreeFolderIds(m0, folderId)
        val freedBlobs = m0.files.filter { it.folder in subFolders }.map { it.blob }
        val res = mutate.invoke { m ->
            m.copy(files = m.files.filterNot { it.folder in subFolders }, fileFolders = m.fileFolders.filterNot { it.id in subFolders })
        }
        if (res is Outcome.Ok) blobRepo.deleteBlobs(freedBlobs)
    }
    fun uploadPicked(name: String, mime: String, size: Long, open: () -> java.io.InputStream) = viewModelScope.launch {
        _busy.value = true
        val up = blobRepo.upload(name, mime, size, open)
        if (up is Outcome.Ok) {
            val cwd = stack.last()
            mutate.invoke { m -> m.copy(files = m.files + FileEntry(id = up.value.id, blob = up.value.id, encFileKey = up.value.encFileKey, name = name, mime = mime, size = size, folder = cwd)) }
        }
        _busy.value = false
    }
    private fun collectSubtreeFolderIds(m: WorkspaceManifest, root: String): Set<String> {
        val out = mutableSetOf(root); var changed = true
        while (changed) { changed = false; for (f in m.fileFolders) if (f.parent in out && out.add(f.id)) changed = true }
        return out
    }
    private fun newId(): String = java.util.UUID.randomUUID().toString()
```
Add a `_busy` `MutableStateFlow<Boolean>` exposed as `busy` for upload/download progress. Add `fileById(id)` (may already exist from Phase 2's file detail).

- [ ] **Step 2: Extend `FilesViewModelTest`** — add a test using fakes for `MutateWorkspace` + `FileBlobRepository` that `createFolder("New")` results in the folder appearing (fake `MutateWorkspace` applies the mutation onto the cache and calls `cache.set`). Assert `state.value.folders` contains "New". Keep existing Phase-2 tests green (constructor gained params — update the existing test's VM construction with fakes).

- [ ] **Step 3: Run tests + build → green. Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace/files app/src/test
git commit -m "feat: files viewmodel actions (upload, delete, rename, new folder)"
```

---

## Task 7: Files UI — FAB, dialogs, viewer, SAF

**Files:** Modify `ui/workspace/files/FilesScreen.kt`. Create `ui/workspace/files/FileViewerScreen.kt`, `ui/workspace/files/SafLaunchers.kt`. Add strings.

- [ ] **Step 1: `SafLaunchers.kt`** — pick-to-upload + save-to-export helpers

```kotlin
package de.ledgerline.app.ui.workspace.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

data class PickedFile(val name: String, val mime: String, val size: Long, val uri: Uri)

fun queryPicked(context: Context, uri: Uri): PickedFile {
    var name = "file"; var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) { if (ni >= 0) name = c.getString(ni); if (si >= 0 && !c.isNull(si)) size = c.getLong(si) }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return PickedFile(name, mime, size, uri)
}
```
The screen uses `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` for upload (returns a `Uri` → `queryPicked` → `vm.uploadPicked(name, mime, size) { context.contentResolver.openInputStream(uri)!! }`) and `ActivityResultContracts.CreateDocument(mime)` for export (returns a `Uri` → `vm.exportTo(...)` writing decrypted chunks to `contentResolver.openOutputStream(uri)`).

- [ ] **Step 2: `FileViewerScreen.kt`** — image + text viewer from in-memory bytes

```kotlin
package de.ledgerline.app.ui.workspace.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import de.ledgerline.app.domain.model.FileEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(file: FileEntry, bytes: ByteArray, onBack: () -> Unit, onSave: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(file.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
            actions = { TextButton(onClick = onSave) { Text("Save") } },
        ) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                file.mime.startsWith("image/") -> {
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = file.name, modifier = Modifier.fillMaxSize())
                    else Text("Cannot display image", Modifier.padding(24.dp))
                }
                file.mime.startsWith("text/") || file.mime == "application/json" || file.mime == "application/xml" ->
                    Text(String(bytes), Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()))
                else -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Preview not available for this file type.")
                    Spacer(Modifier.height(8.dp)); Button(onClick = onSave) { Text("Save to device") }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire `FilesScreen.kt`** — FAB (upload / new folder menu), per-row long-press/overflow (open, rename, delete), open→download→viewer, busy overlay, export launcher, confirm dialogs. Use `vm.busy` for a progress overlay. Keep the existing folder navigation + pull-to-refresh. On file tap: launch `vm.openFile(file)` which downloads bytes (via `FileBlobRepository.downloadToBytes`) into a `viewerState`; render `FileViewerScreen` when set. On Save from the viewer: launch the `CreateDocument` SAF with `file.mime`/`file.name`, then `vm.exportTo(uri, file)`.

  Add to `FilesViewModel`: `openFile(file)` → sets `_viewer` state (loading→bytes/error); `exportTo(uri, file)` → `blobRepo.downloadTo(file.blob, file.encFileKey) { chunk -> outputStream.write(chunk) }`. Provide `busy`/`viewer` StateFlows.

- [ ] **Step 4: Strings (en + de)** — `file_upload`, `file_new_folder`, `file_rename`, `file_delete`, `file_delete_confirm`, `file_save`, `file_open`, `file_preview_unavailable`, `file_usage` (`"%1$s of %2$s used"`), `folder_name`, `action_create`, plus German translations.

- [ ] **Step 5: Build + install + commit**

Run: `./gradlew :app:assembleDebug`. Install on `62021JEBF09273`, launch, `adb -s 62021JEBF09273 logcat -d | grep -iE "FATAL|AndroidRuntime" | grep -i ledgerline || echo NO_CRASH`.
```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace/files app/src/main/res
git commit -m "feat: files UI — upload, viewer, save-to-SAF, folder/file management"
```

---

## Task 8: DI, usage display, verification, finish

- [ ] **Step 1: DI + usage** — ensure `FileBlobRepository`, `MutateWorkspace` binding are provided; add a `filesUsage()` call surfaced in the Files top area (`used / quota` via `humanSize`). Add `di` provider if any new interface needs binding.
- [ ] **Step 2: Full test sweep** — `./gradlew :app:testDebugUnitTest` (all green) and `ANDROID_SERIAL=62021JEBF09273 ./gradlew :app:connectedDebugAndroidTest` (crypto incl. `SealManifestTest` + `ContentCipherTest`, keystore — all pass).
- [ ] **Step 3: Release R8** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL (add keep rules if a stream/serializer needs it).
- [ ] **Step 4: Hardening greps** — `grep -q FLAG_SECURE ... MainActivity.kt`; `grep -rnE 'Log\.(d|v|i|w|e)\(.*(token|passphrase|vk|vault|kek|manifest|fk|filekey)' app/src/main || echo CLEAN`.
- [ ] **Step 5: On-device smoke (human)** — upload a photo + a text file to `home.kiefer-networks.de`; see them appear; open each (image renders, text shows); Save one via SAF; delete one; create + rename a folder; confirm 409-merge by editing on web + app concurrently (best-effort).
- [ ] **Step 6: Finish** — invoke `superpowers:finishing-a-development-branch`: merge `feature/phase3` → `develop` → `main`, tag `v0.3.0`.

---

## Self-Review Notes (author checklist — completed)

- **Spec coverage:** sealManifest+Padmé (T1); streaming content cipher (T2); files/store endpoints+DTOs (T3); 409-merge save (T4); blob repo download/upload/delete-throttle (T5); FilesViewModel actions (T6); UI FAB/viewer/SAF/dialogs (T7); DI+usage+verify+finish (T8). Security (in-memory decrypt, SAF export no-temp, throttled delete) covered in T5/T7. All spec sections map to a task.
- **Placeholder scan:** every code step has literal code; notes call out lazysodium-API and streaming-refinement verification points (not placeholders — the on-device tests are the acceptance criteria).
- **Type consistency:** `Crypto.{sealManifest,newContentEncryptor,contentDecryptor,u32le,readU32le,contentChunkSize}`, `ContentEncryptor/Decryptor`, `WorkspaceRepository.save(mutate)`, `MutateWorkspace.invoke`, `FileBlobRepository.{upload,downloadToBytes,downloadTo,deleteBlobs}`, `UploadedBlob(id,encFileKey,size)`, manifest model names — consistent across tasks. Existing `Crypto` fakes (UnlockVaultTest, WorkspaceSaveTest) must gain stubs for the new interface methods (called out in T4 Step 3).
- **Known risks flagged inline:** lazysodium 5.1.0 secretstream/random API arities (T2); download buffers full ciphertext then frame-decrypts, true-streaming deferred (T5); pinned-vs-cleartext test api provider for MockWebServer (T4/T5); SAF Uri stream lifetimes (T7).
```
