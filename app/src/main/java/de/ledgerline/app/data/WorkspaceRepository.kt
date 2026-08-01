package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.crypto.CanonicalJson
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.domain.model.BookmarksManifest
import de.ledgerline.app.domain.model.ContactsManifest
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.FilesRoot
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.NotesManifest
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.model.TodosManifest
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MultipartBody
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads + decrypts the workspace over the pinned, authenticated session.
 *
 * **Store v3:** the monolith `/store` was removed server-side (bare `/store` now
 * 404s). Each workspace module lives in its own sealed store
 * `GET/PUT /api/v1/store/{module}` (`{ciphertext, version}`). This repository fans
 * out to the four workspace modules (notes, todos, bookmarks, contacts) and
 * assembles the aggregate [WorkspaceManifest] the rest of the app consumes — so
 * the app-facing contract ([load]/[save] over `WorkspaceManifest`) is unchanged.
 *
 * Files (and their folders) live in the **sharded** `/files/store` (Store v3), assembled
 * and written by the same content-addressed engine as the gallery ([FilesShardWriter] +
 * [FileRecordCodec], byte-compatible with the web `makeShardedStore`). Records/folders are
 * kept as raw JSON on load and overlaid on save so no web field is ever dropped.
 */
@Singleton
class WorkspaceRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: WorkspaceCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val degraded: de.ledgerline.app.core.offline.DegradedState,
    private val blobCache: de.ledgerline.app.core.offline.BlobDiskCache,
    private val syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val apiProvider: (Session) -> LedgerlineApi,
) : de.ledgerline.app.core.offline.SyncableStore {
    /** Production constructor used by Hilt. */
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: WorkspaceCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        degraded: de.ledgerline.app.core.offline.DegradedState,
        blobCache: de.ledgerline.app.core.offline.BlobDiskCache,
        syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
        connectivity: de.ledgerline.app.core.offline.Connectivity,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
        cache,
        storeCache,
        offlineFlags,
        degraded,
        blobCache,
        syncOutbox,
        connectivity,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        /** Offline-cache key for the sharded files-store **root** envelope. */
        const val FILES_ROOT_KEY = "workspace_files_root"
        /** Offline-cache key for the sharded notes-store **root** envelope. */
        const val NOTES_ROOT_KEY = "workspace_notes_root"
        /** SyncOutbox key for offline workspace write deltas (one aggregate delta for all modules). */
        const val OUTBOX = "workspace"
        val LIST_KEYS = listOf("notes", "todos", "todoLists", "bookmarks", "bookmarkFolders", "contacts", "files", "fileFolders")
    }

    override val syncLabel: String = "workspace"

    // ---- Offline write outbox (aggregate record-level delta across all modules) ----
    private fun collectionsOf(m: WorkspaceManifest): Map<String, Map<String, JsonObject>> = mapOf(
        "notes" to m.notes.associate { it.id to WorkspaceRecordCodec.encodeNote(it) },
        "todos" to m.todos.associate { it.id to WorkspaceRecordCodec.encodeTodo(it) },
        "todoLists" to m.todoLists.associate { it.id to WorkspaceRecordCodec.encodeTodoList(it) },
        "bookmarks" to m.bookmarks.associate { it.id to WorkspaceRecordCodec.encodeBookmark(it) },
        "bookmarkFolders" to m.bookmarkFolders.associate { it.id to WorkspaceRecordCodec.encodeBookmarkFolder(it) },
        "contacts" to m.contacts.associate { it.id to WorkspaceRecordCodec.encodeContact(it) },
        "files" to m.files.associate { it.id to FileRecordCodec.encodeFile(it, fileRawById[it.id]) },
        "fileFolders" to m.fileFolders.associate { it.id to FileRecordCodec.encodeFolder(it, folderRawById[it.id]) },
    )

    private fun applyDelta(m: WorkspaceManifest, delta: de.ledgerline.app.core.offline.StoreDelta): WorkspaceManifest {
        fun <T> merge(list: List<T>, key: String, id: (T) -> String, decode: (JsonObject) -> T): List<T> {
            val cd = delta.collections[key] ?: return list
            if (cd.isEmpty) return list
            val byId = list.associateByTo(LinkedHashMap()) { id(it) }
            cd.deletes.forEach { byId.remove(it) }
            cd.upserts.forEach { (rid, obj) -> byId[rid] = decode(obj) }
            return byId.values.toList()
        }
        return m.copy(
            notes = merge(m.notes, "notes", { it.id }, WorkspaceRecordCodec::decodeNote),
            todos = merge(m.todos, "todos", { it.id }, WorkspaceRecordCodec::decodeTodo),
            todoLists = merge(m.todoLists, "todoLists", { it.id }, WorkspaceRecordCodec::decodeTodoList),
            bookmarks = merge(m.bookmarks, "bookmarks", { it.id }, WorkspaceRecordCodec::decodeBookmark),
            bookmarkFolders = merge(m.bookmarkFolders, "bookmarkFolders", { it.id }, WorkspaceRecordCodec::decodeBookmarkFolder),
            contacts = merge(m.contacts, "contacts", { it.id }, WorkspaceRecordCodec::decodeContact),
            files = merge(m.files, "files", { it.id }) { obj -> FileRecordCodec.decodeFile(obj).also { fileRawById[it.id] = obj } },
            fileFolders = merge(m.fileFolders, "fileFolders", { it.id }) { obj -> FileRecordCodec.decodeFolder(obj).also { folderRawById[it.id] = obj } },
        )
    }

    private fun withPending(m: WorkspaceManifest, vk: ByteArray): WorkspaceManifest =
        syncOutbox.pending(OUTBOX, vk)?.let { applyDelta(m, it) } ?: m

    private fun enqueueOffline(vk: ByteArray, base: WorkspaceManifest, next: WorkspaceManifest): Outcome<Workspace> {
        val delta = de.ledgerline.app.core.offline.StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(OUTBOX, delta, vk)
        val result = Workspace(next, 0)
        cache.set(result)
        return Outcome.Ok(result)
    }

    /**
     * Notes graduated to the **sharded** `/notes/store` (Store v3, web `LLNotesStore` —
     * prefix `/notes`, recordKey `notes`, no collections). Same content-addressed engine as
     * files/gallery. The old monolith `/store/notes` is read once for a one-time migration
     * (below) and then blanked, byte-exact with the web dual-read migration.
     */
    private val notesEngine by lazy {
        ShardedStoreEngine(
            crypto = crypto,
            blobCache = blobCache,
            storeCache = storeCache,
            offlineFlags = offlineFlags,
            rootCacheKey = NOTES_ROOT_KEY,
            storeGet = { apiProvider(sessionHolder.get()!!).notesStore() },
            storePut = { apiProvider(sessionHolder.get()!!).notesStorePut(it) },
            rawBlob = { apiProvider(sessionHolder.get()!!).rawNote(it) },
            uploadBlobApi = { apiProvider(sessionHolder.get()!!).uploadNote(it) },
            reconcile = { refs -> apiProvider(sessionHolder.get()!!).notesReconcile(de.ledgerline.app.data.remote.dto.ReconcileRequest(refs)) },
            rawBatch = { refs -> apiProvider(sessionHolder.get()!!).notesRawBatch(de.ledgerline.app.data.remote.dto.ReconcileRequest(refs)) },
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonEncoder = Json { encodeDefaults = true }
    // coerceInputValues: the sharded files root may carry `"files": null` alongside the shard
    // list; coercing a JSON null on a non-null defaulted field to its default keeps decode robust.
    private val filesJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Per-module server version, tracked across load/save for optimistic concurrency. */
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ---- Sharded /files/store slice state (Store v3) --------------------------
    /** Server version of the sharded files store (separate optimistic-concurrency counter). */
    @Volatile private var filesVersion = 0
    /** Prior sealed-root state for dirty-save blob reuse; rebased on 409. */
    @Volatile private var priorFilesRoot = FilesShardWriter.RootState()
    // Raw record JSON captured on load so a save re-emits every web field byte-exact (no loss).
    private val fileRawById = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    private val folderRawById = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    /**
     * One workspace module store. [encode] serialises the module's slice of the
     * aggregate to the wire JSON that gets sealed; [merge] decodes a fetched module
     * JSON and replaces that slice in an aggregate; [changed] reports whether the
     * slice differs between two aggregates.
     */
    private inner class ModuleSpec(
        val key: String,
        val encode: (WorkspaceManifest) -> String,
        val merge: (WorkspaceManifest, String) -> WorkspaceManifest,
        val changed: (WorkspaceManifest, WorkspaceManifest) -> Boolean,
    ) {
        fun cacheKey() = "workspace_$key"
        /** The sealed wire JSON of an empty module (used when the server slot is null). */
        fun emptyPlain() = encode(WorkspaceManifest())
    }

    private val specs = listOf(
        ModuleSpec(
            key = "todos",
            encode = { m ->
                encModule {
                    it["todos"] = arr(m.todos.map(WorkspaceRecordCodec::encodeTodo))
                    it["todoLists"] = arr(m.todoLists.map(WorkspaceRecordCodec::encodeTodoList))
                }
            },
            merge = { m, plain ->
                m.copy(
                    todos = records(plain, "todos").map(WorkspaceRecordCodec::decodeTodo),
                    todoLists = records(plain, "todoLists").map(WorkspaceRecordCodec::decodeTodoList),
                )
            },
            changed = { a, b -> a.todos != b.todos || a.todoLists != b.todoLists },
        ),
        ModuleSpec(
            key = "bookmarks",
            encode = { m ->
                encModule {
                    it["bookmarks"] = arr(m.bookmarks.map(WorkspaceRecordCodec::encodeBookmark))
                    it["bookmarkFolders"] = arr(m.bookmarkFolders.map(WorkspaceRecordCodec::encodeBookmarkFolder))
                }
            },
            merge = { m, plain ->
                m.copy(
                    bookmarks = records(plain, "bookmarks").map(WorkspaceRecordCodec::decodeBookmark),
                    bookmarkFolders = records(plain, "bookmarkFolders").map(WorkspaceRecordCodec::decodeBookmarkFolder),
                )
            },
            changed = { a, b -> a.bookmarks != b.bookmarks || a.bookmarkFolders != b.bookmarkFolders },
        ),
        ModuleSpec(
            key = "contacts",
            encode = { m -> encModule { it["contacts"] = arr(m.contacts.map(WorkspaceRecordCodec::encodeContact)) } },
            merge = { m, plain -> m.copy(contacts = records(plain, "contacts").map(WorkspaceRecordCodec::decodeContact)) },
            changed = { a, b -> a.contacts != b.contacts },
        ),
    )

    /** Build a `{v:3, …}` module manifest JSON string; [fill] adds the record arrays. */
    private inline fun encModule(fill: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
        val out = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("v" to kotlinx.serialization.json.JsonPrimitive(3))
        fill(out)
        return kotlinx.serialization.json.JsonObject(out).toString()
    }

    private fun arr(items: List<JsonObject>): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(items)

    /** The [key] record array of a decrypted module manifest, as raw [JsonObject]s. */
    private fun records(plain: String, key: String): List<JsonObject> =
        (json.parseToJsonElement(plain).jsonObject[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()

    // Internal signals used to map per-module fetch failures to Outcome errors.
    private class AuthException : Exception()
    private class DecryptException : Exception()
    private class NetworkException : Exception()

    private data class ModuleLoad(val version: Int, val plain: String?)

    /** Files blob storage usage: (used bytes, quota bytes). Null on any failure. */
    suspend fun filesUsage(): Pair<Long, Long>? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).filesUsage()
            if (!res.isSuccessful) return null
            val body = res.body() ?: return null
            body.used to body.quota
        } catch (_: Exception) {
            null
        }
    }

    // ---- Sharded files store: load/assemble + dirty-save ----------------------

    private fun rootStateFrom(root: FilesRoot) = FilesShardWriter.RootState(
        shardBits = root.shardBits,
        shards = root.shards,
        folders = root.foldersRef?.let { FilesShardWriter.CollDesc(it, root.foldersKey ?: "", root.foldersHash ?: "") },
    )

    /**
     * Load the sharded `/files/store` slice: root → shard blobs (parallel) → folders collection.
     * A missing/failed shard THROWS (mirrors web/gallery — never silently drop files, which a
     * reconcile could then free). Best-effort at the top level: a transient network failure or a
     * non-2xx returns an EMPTY slice (files unavailable, no worse than before this migration)
     * rather than failing the whole workspace load; 401 still forces logout.
     */
    private suspend fun loadFilesSlice(api: LedgerlineApi, vk: ByteArray): Pair<List<FileEntry>, List<NamedFolder>> {
        val res = try {
            api.filesStore()
        } catch (_: Exception) {
            return cachedFilesSliceOr(api, vk) // transient network error: assemble from the offline cache
        }
        if (res.code() == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthException()
        if (!res.isSuccessful) return cachedFilesSliceOr(api, vk)

        val body = res.body()!!
        filesVersion = body.version
        fileRawById.clear(); folderRawById.clear()
        val ct = body.ciphertext
            ?: run { priorFilesRoot = FilesShardWriter.RootState(); return emptyList<FileEntry>() to emptyList() }
        // Persist the root envelope for cold offline assembly (opaque ciphertext).
        if (offlineFlags.enabled()) storeCache.put(FILES_ROOT_KEY, StoreEnvelope(ct, body.version))
        val plain = crypto.openManifest(ct, vk) ?: throw DecryptException()
        val root = filesJson.decodeFromString(FilesRoot.serializer(), plain)
        return assembleFilesSlice(api, root, vk, allowNetwork = true)
    }

    /**
     * Assemble the files slice from a decoded [root]: shard blobs (parallel) + folders collection.
     * Online ([allowNetwork]) fetches missing blobs and writes their ciphertext through to the
     * offline cache; offline it reads shard/folder blobs from the cache only (uncached slices are
     * simply absent). A durable 404 marks the store degraded (descriptor kept, writes frozen);
     * any other online blob error throws (recoverable — never silently drop files).
     */
    private suspend fun assembleFilesSlice(
        api: LedgerlineApi,
        root: FilesRoot,
        vk: ByteArray,
        allowNetwork: Boolean,
    ): Pair<List<FileEntry>, List<NamedFolder>> {
        priorFilesRoot = rootStateFrom(root)
        degraded.setFiles(false)
        val files = if (root.shards.isNotEmpty()) {
            coroutineScope {
                root.shards.map { s -> async { fetchFilesShard(api, s, vk, allowNetwork) } }.awaitAll()
            }.flatMap { it ?: emptyList() }.map { obj -> FileRecordCodec.decodeFile(obj).also { fileRawById[it.id] = obj } }
        } else {
            root.files
        }
        val folders = if (root.foldersRef != null) {
            val desc = de.ledgerline.app.domain.model.GalleryShard(ref = root.foldersRef!!, key = root.foldersKey ?: "")
            fetchFilesShard(api, desc, vk, allowNetwork).orEmpty()
                .map { obj -> FileRecordCodec.decodeFolder(obj).also { folderRawById[it.id] = obj } }
        } else {
            emptyList()
        }
        return files to folders
    }

    /**
     * Offline files slice: decrypt the cached files **root** and assemble it from the locally
     * cached shard/folder blobs (no network). Falls back to whatever is already in the in-memory
     * workspace cache when offline caching is off, no root is cached, or the root fails to
     * decrypt/decode — never blanking a tab that already had content.
     */
    private suspend fun cachedFilesSliceOr(api: LedgerlineApi, vk: ByteArray): Pair<List<FileEntry>, List<NamedFolder>> {
        if (offlineFlags.enabled()) {
            storeCache.get(FILES_ROOT_KEY)?.ciphertext?.let { ct ->
                crypto.openManifest(ct, vk)?.let { plain ->
                    runCatching {
                        val root = filesJson.decodeFromString(FilesRoot.serializer(), plain)
                        filesVersion = storeCache.get(FILES_ROOT_KEY)!!.version
                        return assembleFilesSlice(api, root, vk, allowNetwork = false)
                    }
                }
            }
        }
        return cache.value.value?.manifest?.let { it.files to it.fileFolders } ?: (emptyList<FileEntry>() to emptyList())
    }

    /**
     * Fetch + decrypt one files shard's records. Cache-first (content-addressed refs are
     * immutable): a cached hit skips the network. On a miss with [allowNetwork], fetch
     * (404-retried) and write the ciphertext through to the offline cache. A persistent 404 marks
     * the store degraded and returns null (records skipped, descriptor kept). Offline a cache miss
     * returns null (those records unavailable until the next online load). Non-404 online throws.
     */
    private suspend fun fetchFilesShard(
        api: LedgerlineApi,
        s: de.ledgerline.app.domain.model.GalleryShard,
        vk: ByteArray,
        allowNetwork: Boolean,
    ): List<JsonObject>? {
        blobCache.get(s.ref)?.let { cipher ->
            val bytes = BlobDownloader.decrypt(cipher, s.key, vk, crypto)
            return filesJson.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
        }
        if (!allowNetwork) return null
        var attempt = 0
        while (true) {
            val r = api.rawFile(s.ref) // network/other throws → recoverable, fail the load rather than lose data
            if (r.isSuccessful) {
                val cipher = r.body()!!.bytes()
                if (offlineFlags.enabled()) blobCache.put(s.ref, cipher)
                val bytes = BlobDownloader.decrypt(cipher, s.key, vk, crypto)
                return filesJson.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
            }
            if (r.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                if (attempt < 3) { kotlinx.coroutines.delay(500L * (1 shl attempt)); attempt++; continue }
                degraded.setFiles(true)
                return null // durably missing: skip records, keep the descriptor, freeze writes
            }
            error("files shard ${s.ref}: http ${r.code()}") // other non-2xx: recoverable → throw
        }
    }

    /** Encrypt (secretstream + Padmé) + upload [bytes] as a files content blob → id + wrapped key. */
    private suspend fun uploadFilesBytes(api: LedgerlineApi, vk: ByteArray, bytes: ByteArray, name: String): UploadedBlob? {
        val enc = crypto.newContentEncryptor(vk)
        val reqBody = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        return try {
            val part = MultipartBody.Part.createFormData("file", name, reqBody)
            val res = api.uploadFile(part)
            if (!res.isSuccessful) null else UploadedBlob(res.body()!!.id, enc.sealKey(), bytes.size.toLong())
        } catch (_: Exception) { null }
    }

    private fun newFilesWriter(api: LedgerlineApi, vk: ByteArray) = FilesShardWriter(
        encodeFile = { f -> FileRecordCodec.encodeFile(f, fileRawById[f.id]) },
        encodeFolder = { fo -> FileRecordCodec.encodeFolder(fo, folderRawById[fo.id]) },
        uploadBlob = { b, n -> uploadFilesBytes(api, vk, b, n) },
    )

    // ---- Sharded /notes/store slice (Store v3) --------------------------------

    /**
     * Load the sharded notes slice via [notesEngine] (root → shard blobs, cache-first). While the
     * sharded store is still empty, run the one-time monolith migration ([migrateNotesFromMonolith])
     * so notes written by a pre-sharded client (or another mobile client) still surface. A 401
     * surfaces as [AuthException] (forced-logout path); every other failure yields an empty slice
     * (the engine already falls back to the offline cache).
     */
    private suspend fun loadNotesSlice(vk: ByteArray): List<de.ledgerline.app.domain.model.Note> {
        val loaded = try {
            notesEngine.load(vk)
        } catch (_: ShardedStoreEngine.AuthException) {
            throw AuthException()
        }
        var notes = loaded.records.map(WorkspaceRecordCodec::decodeNote)
        if (notes.isEmpty()) migrateNotesFromMonolith(vk)?.let { notes = it }
        return notes
    }

    /**
     * One-time dual-read migration from the old monolith `/store/notes` to the sharded
     * `/notes/store` (byte-exact with the web `migrateFromMonolith`): read the monolith, and if it
     * still holds notes, move them into the sharded store, then blank the monolith
     * (`{v:3,notes:[]}`) so a later "delete all" can never re-import them. Best-effort — any failure
     * just leaves the notes where they are (the empty-sharded guard retries next load). Returns the
     * migrated notes, or null when there was nothing to migrate.
     */
    private suspend fun migrateNotesFromMonolith(vk: ByteArray): List<de.ledgerline.app.domain.model.Note>? {
        val session = sessionHolder.get() ?: return null
        val api = apiProvider(session)
        val res = try { api.moduleStore("notes") } catch (_: Exception) { return null }
        if (!res.isSuccessful) return null
        val body = res.body() ?: return null
        val ct = body.ciphertext ?: return null
        val plain = crypto.openManifest(ct, vk) ?: return null
        val old = records(plain, "notes").map(WorkspaceRecordCodec::decodeNote)
        if (old.isEmpty()) return null
        val recs = old.map { it.id to WorkspaceRecordCodec.encodeNote(it) }
        if (notesEngine.sealAndPut(vk, recs, emptyList(), notesEngine.version) !is ShardedStoreEngine.PutOutcome.Ok) return null
        runCatching {
            val empty = crypto.sealManifest(encModule { it["notes"] = arr(emptyList()) }, vk)
            api.putModuleStore("notes", StorePutRequest(empty, body.version))
        }
        return old
    }

    // On Dispatchers.IO: opening + JSON-decoding every module + the sharded files slice is
    // CPU/IO-heavy and must not block the caller's main thread (large stores would ANR).
    /**
     * Recover NOTES from a retained history-version sealed root [ciphertext] (`/notes/store/history/{v}`):
     * decode the old notes and re-add any whose id is missing from the current workspace (never
     * overwrites a live note). Returns the count restored, or -1 on failure.
     */
    suspend fun recoverNotesFromHistoryRoot(ciphertext: String): Int = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext -1
        val loaded = runCatching { notesEngine.historyLoad(ciphertext, vk) }.getOrNull() ?: return@withContext -1
        val cur = cache.value.value?.manifest ?: when (val l = load()) {
            is Outcome.Ok -> l.value.manifest
            is Outcome.Err -> return@withContext -1
        }
        val have = cur.notes.mapTo(HashSet()) { it.id }
        val add = loaded.records.map(WorkspaceRecordCodec::decodeNote).filter { it.id !in have }
        if (add.isEmpty()) return@withContext 0
        val out = save { m -> m.copy(notes = m.notes + add) }
        if (out is Outcome.Ok) add.size else -1
    }

    suspend fun load(): Outcome<Workspace> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        // Cache-first: assemble the workspace from the disk cache and paint it immediately so the
        // Files/Notes/Todos/… tabs show content before the network round-trip below refreshes it.
        // Best-effort — a cold cache just falls through to the network load.
        if (cache.value.value == null) {
            runCatching { cachedWorkspace(api, vk) }.getOrNull()?.let { cache.set(Workspace(withPending(it.manifest, vk), it.version)) }
        }
        try {
            // Layer any un-synced offline edits on top of the fresh server aggregate.
            Outcome.Ok(Workspace(withPending(fetchAggregate(api, vk), vk), 0))
        } catch (_: AuthException) {
            // 401 → forced-logout path; never fall back to cache.
            Outcome.Err(ErrorKind.HTTP)
        } catch (_: DecryptException) {
            Outcome.Err(ErrorKind.DECRYPT)
        } catch (e: Exception) {
            Outcome.Err(ErrorKind.NETWORK, e)
        }
    }

    /**
     * Fetch + assemble the CLEAN server aggregate (modules + files slice + notes), refreshing the
     * per-store version state. No offline-delta layering, no caching — used by [load] (which layers
     * + caches) and by [replayPending] (which needs a clean base to diff the pending delta against).
     * Throws [AuthException]/[DecryptException]/network exceptions like the module fetch.
     */
    private suspend fun fetchAggregate(api: LedgerlineApi, vk: ByteArray): WorkspaceManifest {
        val loaded = coroutineScope {
            val filesDeferred = async { loadFilesSlice(api, vk) }
            val notesDeferred = async { loadNotesSlice(vk) }
            val mods = specs.map { spec -> async { spec to fetchModule(api, spec, vk) } }.awaitAll()
            Triple(mods, filesDeferred.await(), notesDeferred.await())
        }
        val (mods, filesSlice, notesSlice) = loaded
        var agg = WorkspaceManifest(v = 3)
        for ((spec, ml) in mods) {
            versions[spec.key] = ml.version
            if (ml.plain != null) agg = spec.merge(agg, ml.plain)
        }
        return agg.copy(files = filesSlice.first, fileFolders = filesSlice.second, notes = notesSlice)
    }

    /**
     * Assemble the whole workspace from the disk cache only (no network), for a cache-first first
     * paint. Modules, the files slice, and notes are read from their offline caches; returns null
     * when nothing is cached (so the caller falls through to the network load without blanking).
     */
    private suspend fun cachedWorkspace(api: LedgerlineApi, vk: ByteArray): Workspace? {
        if (!offlineFlags.enabled()) return null
        var agg = WorkspaceManifest(v = 3)
        var any = false
        for (spec in specs) {
            val ml = cachedModule(spec, vk) ?: continue
            versions[spec.key] = ml.version
            if (ml.plain != null) { agg = spec.merge(agg, ml.plain); any = true }
        }
        val files = runCatching { cachedFilesSliceOr(api, vk) }.getOrNull() ?: (emptyList<FileEntry>() to emptyList())
        val notes = runCatching { notesEngine.loadCached(vk)?.records?.map(WorkspaceRecordCodec::decodeNote) }
            .getOrNull().orEmpty()
        if (files.first.isNotEmpty() || files.second.isNotEmpty() || notes.isNotEmpty()) any = true
        agg = agg.copy(files = files.first, fileFolders = files.second, notes = notes)
        return if (any) Workspace(agg, 0) else null
    }

    /**
     * Fetch one module: network-first, falling back to the offline cache on a
     * network error. Throws [AuthException] on 401, [DecryptException] on a decrypt
     * failure, [NetworkException] when the network fails and no cache is available.
     */
    private suspend fun fetchModule(api: LedgerlineApi, spec: ModuleSpec, vk: ByteArray): ModuleLoad {
        val res = try {
            api.moduleStore(spec.key)
        } catch (e: Exception) {
            return cachedModule(spec, vk) ?: throw NetworkException()
        }
        when {
            res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> throw AuthException()
            !res.isSuccessful -> return cachedModule(spec, vk) ?: throw NetworkException()
        }
        val body = res.body()!!
        if (offlineFlags.enabled()) storeCache.put(spec.cacheKey(), StoreEnvelope(body.ciphertext, body.version))
        val plain = body.ciphertext?.let { crypto.openManifest(it, vk) ?: throw DecryptException() }
        return ModuleLoad(body.version, plain)
    }

    /** The offline-cached module envelope decrypted in-memory, or null if unavailable. */
    private fun cachedModule(spec: ModuleSpec, vk: ByteArray): ModuleLoad? {
        if (!offlineFlags.enabled()) return null
        val env = storeCache.get(spec.cacheKey()) ?: return null
        val plain = env.ciphertext?.let { crypto.openManifest(it, vk) ?: return null }
        return ModuleLoad(env.version, plain)
    }

    /**
     * Token-only refresh of the offline cache: fetch each module's sealed envelope
     * and write its ciphertext to disk WITHOUT decrypting (no VK needed). Lets a
     * background sync keep the offline copy current while the vault is locked — the
     * ciphertext is opaque. No-op when offline caching is off / no session; returns
     * true only if every module refreshed.
     */
    suspend fun refreshStoreCache(): Boolean {
        if (!offlineFlags.enabled()) return false
        val session = sessionHolder.get() ?: return false
        val api = apiProvider(session)
        return try {
            var all = true
            for (spec in specs) {
                val res = api.moduleStore(spec.key)
                if (!res.isSuccessful) { all = false; continue }
                val body = res.body() ?: run { all = false; continue }
                storeCache.put(spec.cacheKey(), StoreEnvelope(body.ciphertext, body.version))
            }
            // Also refresh the sharded files-store root (token-only, opaque ciphertext).
            runCatching {
                val fr = api.filesStore()
                if (fr.isSuccessful) fr.body()?.let { storeCache.put(FILES_ROOT_KEY, StoreEnvelope(it.ciphertext, it.version)) }
                else all = false
            }.onFailure { all = false }
            // …and the sharded notes-store root.
            runCatching {
                val nr = api.notesStore()
                if (nr.isSuccessful) nr.body()?.let { storeCache.put(NOTES_ROOT_KEY, StoreEnvelope(it.ciphertext, it.version)) }
                else all = false
            }.onFailure { all = false }
            all
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Optimistic write: apply [mutate] to the aggregate, then PUT each module whose
     * slice changed. On a 409 for a module, reload just that module, re-apply
     * [mutate] on the merged base, and retry (bounded per module). Updates the cache
     * on success.
     *
     * File/folder mutations are rejected ([ErrorKind.HTTP]) until the sharded
     * `/files/store` migration lands (CLAUDE.md §14 R1) — better a loud failure than
     * a silent drop.
     */
    suspend fun save(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Resolve the aggregate base (needed to compute an offline delta).
        val base = cache.value.value?.manifest
            ?: (load() as? Outcome.Ok)?.value?.manifest
            ?: WorkspaceManifest()
        // Offline: queue the edit + optimistic cache instead of the multi-store PUT below.
        if (!connectivity.isOnline()) return@withContext enqueueOffline(vk, base, mutate(base))
        val out = saveOnline(mutate = mutate)
        // Connection dropped mid-flight → fall back to the outbox rather than losing the edit.
        if (out is Outcome.Err && out.kind == ErrorKind.NETWORK) enqueueOffline(vk, base, mutate(base)) else out
    }

    /**
     * Replay pending offline workspace edits onto the current server head. Uses [saveOnline] (which
     * never re-queues) so a failure keeps the outbox for a later retry. [applyDelta] is idempotent,
     * so re-applying the delta onto the already-layered cache base is safe; the per-store 409 loops
     * re-apply it onto the winning server state.
     */
    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(OUTBOX, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(OUTBOX); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        val session = sessionHolder.get() ?: return@withContext false
        // Diff the delta against the CLEAN server head (not the delta-layered cache), so saveOnline
        // actually sees a change to push; its per-store 409 loops re-apply onto the winning state.
        val clean = try { fetchAggregate(apiProvider(session), vk) } catch (_: Exception) { return@withContext false }
        val out = saveOnline(baseOverride = clean) { m -> applyDelta(m, delta) }
        if (out is Outcome.Ok) { syncOutbox.clear(OUTBOX); true } else false
    }

    private suspend fun saveOnline(
        baseOverride: WorkspaceManifest? = null,
        mutate: (WorkspaceManifest) -> WorkspaceManifest,
    ): Outcome<Workspace> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)

        // Establish the aggregate base + per-module versions.
        var curBase = baseOverride ?: cache.value.value?.manifest
        if (curBase == null || versions.size < specs.size) {
            when (val l = load()) {
                is Outcome.Ok -> curBase = baseOverride ?: l.value.manifest
                is Outcome.Err -> return@withContext l
            }
        }

        var curNext = mutate(curBase!!)

        // Sharded /files/store slice: build the v3 root (files → shard blobs, folders →
        // collection blob, reusing unchanged blobs), seal + PUT with the shards[] guard. On
        // 409, reload the winning slice, re-apply mutate, retry — same loop shape as a module.
        if (curNext.files != curBase!!.files || curNext.fileFolders != curBase!!.fileFolders) {
            // Frozen while degraded: a shard blob is missing, so rewriting the root would drop the
            // missing shard's slot and make the loss permanent. Reject the write loudly.
            if (degraded.files.value) return@withContext Outcome.Err(ErrorKind.HTTP)
            val writer = newFilesWriter(api, vk)
            var version = filesVersion
            var attempts = 0
            while (true) {
                if (attempts++ >= 5) return@withContext Outcome.Err(ErrorKind.HTTP)
                val result = writer.build(curNext.files, curNext.fileFolders, priorFilesRoot)
                    ?: return@withContext Outcome.Err(ErrorKind.NETWORK) // a shard/collection upload failed
                val rootCipher = crypto.sealManifest(CanonicalJson.encode(result.rootJson), vk)
                val put = try {
                    api.filesStorePut(StorePutRequest(rootCipher, version, result.shardRefs))
                } catch (e: Exception) {
                    return@withContext Outcome.Err(ErrorKind.NETWORK, e)
                }
                when {
                    put.isSuccessful -> {
                        filesVersion = put.body()?.version ?: (version + 1)
                        priorFilesRoot = result.state
                        // Keep the offline root envelope in step with the write. Newly-written
                        // shard blobs are cached lazily on the next online load (assembleFilesSlice
                        // writes each fetched shard through) — the root always stays consistent.
                        if (offlineFlags.enabled()) storeCache.put(FILES_ROOT_KEY, StoreEnvelope(rootCipher, filesVersion))
                        break
                    }
                    put.code() == 409 -> {
                        // Reload the winning root (rebases version + priorFilesRoot + raw maps),
                        // re-merge onto the base, re-apply mutate.
                        val (sf, sfo) = loadFilesSlice(api, vk)
                        version = filesVersion
                        curBase = curBase!!.copy(files = sf, fileFolders = sfo)
                        curNext = mutate(curBase!!)
                    }
                    else -> return@withContext Outcome.Err(ErrorKind.HTTP)
                }
            }
        }

        // Sharded /notes/store slice: same dirty-save + 409-rebase loop as files, via [notesEngine].
        // On 409 reload the winning notes (rebases version + priorRoot), re-apply mutate, retry.
        if (curNext.notes != curBase!!.notes) {
            var version = notesEngine.version
            var attempts = 0
            while (true) {
                if (attempts++ >= 5) return@withContext Outcome.Err(ErrorKind.HTTP)
                val recs = curNext.notes.map { it.id to WorkspaceRecordCodec.encodeNote(it) }
                when (notesEngine.sealAndPut(vk, recs, emptyList(), version)) {
                    is ShardedStoreEngine.PutOutcome.Ok -> break
                    ShardedStoreEngine.PutOutcome.Conflict -> {
                        val fresh = loadNotesSlice(vk)
                        version = notesEngine.version
                        curBase = curBase!!.copy(notes = fresh)
                        curNext = mutate(curBase!!)
                    }
                    ShardedStoreEngine.PutOutcome.Error -> return@withContext Outcome.Err(ErrorKind.NETWORK)
                }
            }
        }

        for (spec in specs) {
            if (!spec.changed(curBase!!, curNext)) continue
            var version = versions[spec.key] ?: 0
            var attempts = 0
            while (true) {
                if (attempts++ >= 4) return@withContext Outcome.Err(ErrorKind.HTTP)
                val ciphertext = crypto.sealManifest(spec.encode(curNext), vk)
                val put = try {
                    api.putModuleStore(spec.key, StorePutRequest(ciphertext, version))
                } catch (e: Exception) {
                    return@withContext Outcome.Err(ErrorKind.NETWORK, e)
                }
                when {
                    put.isSuccessful -> {
                        val nv = put.body()?.version ?: (version + 1)
                        versions[spec.key] = nv
                        if (offlineFlags.enabled()) storeCache.put(spec.cacheKey(), StoreEnvelope(ciphertext, nv))
                        break
                    }
                    put.code() == 409 -> {
                        // Reload just this module, re-merge, re-apply mutate, retry.
                        val res = try {
                            api.moduleStore(spec.key)
                        } catch (e: Exception) {
                            return@withContext Outcome.Err(ErrorKind.NETWORK, e)
                        }
                        if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
                        val body = res.body()!!
                        version = body.version
                        val freshPlain = body.ciphertext?.let {
                            crypto.openManifest(it, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
                        } ?: spec.emptyPlain()
                        curBase = spec.merge(curBase!!, freshPlain)
                        curNext = mutate(curBase!!)
                    }
                    else -> return@withContext Outcome.Err(ErrorKind.HTTP)
                }
            }
        }

        val result = Workspace(curNext, 0)
        cache.set(result)
        return@withContext Outcome.Ok(result)
    }
}
