# Phase 5a — Offline read (encrypted cache-on-access)

**Goal:** Make already-viewed data usable offline. Cache the sealed manifest
envelopes (`/store`, `/gallery/store`) and blob **ciphertext** on disk exactly as the
server returns them; when offline (or a fetch fails), serve from that cache and
decrypt in-memory with the VK. No prefetch, no write queue (those are 5b/5c).

**Security frame (§9/§11):** what we persist is ciphertext — byte-identical to what
the ZK server stores — so disk caching leaks nothing new. Decryption is always
in-memory and needs the VK, so locked = no access. `allowBackup="false"` already set.
The cache is cleared on device disconnect and on the 401 forced logout.

**Key reality:** notes/bookmarks/todos/the file tree all live in the single `/store`
manifest (small) — caching that manifest makes them offline automatically. Only
**blobs** (file contents, photos/thumbnails) are the large, optional part. So the
user-facing toggles are: master **Offline cache**, **File contents offline**,
**Photos offline**.

---

## A1 — Cache + connectivity infrastructure

### `core/offline/StoreDiskCache.kt` (`@Singleton`)
Persists the raw `{ciphertext, version}` envelope per store key. Root
`context.filesDir/storecache/`; file `<key>.json` where key ∈ {`workspace`,`gallery`}.
```kotlin
@Serializable data class StoreEnvelope(val ciphertext: String?, val version: Int)
class StoreDiskCache(private val root: File) {
    @Inject constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, "storecache"))
    fun get(key: String): StoreEnvelope?      // null if absent/corrupt
    fun put(key: String, env: StoreEnvelope)  // atomic write (tmp + rename)
    fun remove(key: String)
    fun clear()
    fun sizeBytes(): Long
}
```
Serialize with kotlinx. The ciphertext is already sealed with the VK — safe at rest.

### `core/offline/BlobDiskCache.kt` (`@Singleton`)
Stores blob **ciphertext** bytes (exactly what `GET .../raw/{blob}` returns) under
`context.filesDir/blobcache/<blobId>`. Blob ids are UUIDs → safe filenames.
```kotlin
class BlobDiskCache(private val root: File) {
    @Inject constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, "blobcache"))
    fun get(blobId: String): ByteArray?       // null if absent
    fun put(blobId: String, ciphertext: ByteArray)  // atomic write
    fun has(blobId: String): Boolean
    fun remove(blobId: String)
    fun clear()
    fun sizeBytes(): Long                      // sum of file sizes
}
```
Both caches take a `File root` in the primary constructor so JVM tests can point at a
`@TempDir`. `put` writes to `<name>.tmp` then renames (crash-safe).

### `core/offline/Connectivity.kt`
```kotlin
interface Connectivity { fun isOnline(): Boolean }
@Singleton class AndroidConnectivity @Inject constructor(@ApplicationContext ctx: Context) : Connectivity {
    // ConnectivityManager.activeNetwork + NetworkCapabilities.NET_CAPABILITY_INTERNET/VALIDATED
}
```
Bind `Connectivity -> AndroidConnectivity` via `@Binds`. Repos depend on the
interface so JVM tests fake it. AOSP only — no Google.

### `SettingsStore` — offline flags
```kotlin
private val offlineKey = booleanPreferencesKey("offline_cache_enabled")   // master, default true
private val filesBlobsKey = booleanPreferencesKey("offline_files_blobs")  // default false
private val photosBlobsKey = booleanPreferencesKey("offline_photos_blobs")// default false
val offlineEnabled: Flow<Boolean>; val filesBlobsOffline: Flow<Boolean>; val photosBlobsOffline: Flow<Boolean>
suspend fun setOfflineEnabled(b); suspend fun setFilesBlobsOffline(b); suspend fun setPhotosBlobsOffline(b)
```
Add a small `@Singleton OfflinePrefs` wrapper exposing synchronous latest values
(seed with `runBlocking { first() }`, keep live via a collector) so repos can read
the flag without suspending — mirror `BackgroundOpsSetting` from Phase 4d.

### Tests
- `StoreDiskCacheTest` / `BlobDiskCacheTest` (JVM, `@TempDir`): put/get round-trip,
  absent → null, `has`, `remove`, `clear`, `sizeBytes`, atomic overwrite, corrupt
  envelope → null.

## A2 — Repository integration (network-first, cache fallback)

For each read path: try the network; on success write through to the cache **iff the
relevant offline toggle is on**; on a NETWORK failure (offline/timeout) read the
cache and decrypt. Do NOT fall back to cache on 401 (that is auth failure → existing
forced-logout path). Inject `StoreDiskCache`/`BlobDiskCache`/`Connectivity`/
`OfflinePrefs` into the repos (add to both the primary and the `@Inject` secondary
constructors; keep the `forTest` factories compiling — pass no-op fakes there).

### `WorkspaceRepository.load` / `GalleryRepository.load`
- On `res.isSuccessful`: build manifest as today AND, if `offlineEnabled`, `storeCache.put("workspace"|"gallery", StoreEnvelope(body.ciphertext, body.version))`.
- On the `catch (NETWORK)` branch or `!isSuccessful` non-401: if `offlineEnabled`, read `storeCache.get(key)`; if present, decrypt (`crypto.openManifest`) with the VK and return `Outcome.Ok(Workspace/Gallery(manifest, env.version))`; else return the network error as today. (A null-ciphertext envelope → empty manifest, same as online.)
- `save()`'s 409-merge is unchanged; on a successful PUT also `storeCache.put(...)` with the new ciphertext+version when `offlineEnabled` (so the offline copy stays fresh). Compute the sealed ciphertext already available in `save`.

### `FileBlobRepository.streamDecrypted` / `GalleryBlobRepository.download`
- Fetch: `res.body()!!.bytes()` (ciphertext). On success, if the module toggle is on (`filesBlobsOffline` for files, `photosBlobsOffline` for gallery), `blobCache.put(blob, bytes)`. Then decrypt as today.
- On a NETWORK failure fetching: if the module toggle is on, `blobCache.get(blob)`; if present, decrypt it and return — else return the error.
- Keep decryption paths identical; only the byte source changes.

### Tests
- Repo-level: with a fake `Connectivity(false)` + a pre-populated `StoreDiskCache`,
  `load()` returns the cached manifest (use the existing MockWebServer test harness
  for the online write-through; for the offline read, a fake api that throws / a
  `Connectivity` returning false). At minimum: online load writes the envelope to the
  cache; a subsequent offline load returns it. Blob: online download caches
  ciphertext; offline download serves it. Use the `forTest` harness style already in
  the repos; a real `Crypto` isn't available off-device, so cover the cache
  read/write wiring with a fake crypto where decryption is stubbed, OR assert the
  cache is populated/consumed via a spy. Keep JVM-runnable.

## A3 — Settings UI + cache lifecycle

### Settings section "Offline"
In `SettingsScreen`/`SettingsViewModel`: a new section with
- master `Switch` "Offline cache" (`settings_offline_title` / subtitle),
- `Switch` "File contents offline" (`settings_offline_files`), enabled only when master on,
- `Switch` "Photos offline" (`settings_offline_photos`), enabled only when master on,
- a read-only cache-size line (`settings_offline_size`, humanSize of `storeCache.sizeBytes()+blobCache.sizeBytes()`),
- a "Clear cache" button (`settings_offline_clear`) → clears both disk caches (confirm dialog).
Wire through `SettingsViewModel` to `SettingsStore` + the caches. Strings in both
`values/` and `values-de/`.

### Cache lifecycle
- On device disconnect (existing Settings "Disconnect this device" flow): also
  `storeCache.clear(); blobCache.clear()`.
- On the 401 forced logout: add `storeCache.clear(); blobCache.clear()` to
  `ForceLogoutImpl.invoke()` (inject both). (Do NOT clear on a normal lock — the
  ciphertext cache must survive lock per §11; `VaultLocker.lock()` stays as is.)

### Tests
- `ForceLogoutImplTest`: extend to assert both disk caches `clear()` invoked.

## Verification
- `./gradlew :app:testDebugUnitTest` + `:app:assembleDebug` + `:app:assembleRelease`.
- On-device smoke: view some photos/files online (populates cache), enable the
  toggles, turn on airplane mode, reopen the app + unlock → previously-viewed
  gallery/manifest still load; a never-viewed blob shows its normal error. Confirm
  "Clear cache" empties it and the size line updates. Confirm no plaintext files land
  in `filesDir` (only ciphertext under blobcache/ + sealed envelopes under
  storecache/).

## Out of scope (later)
- Prefetch (all/favorites/recent/thumbnails-only) + Wi-Fi/charging WorkManager + size
  limit/LRU eviction → **5c**.
- Offline write queue (mutations + uploads while offline) → **5b**.
