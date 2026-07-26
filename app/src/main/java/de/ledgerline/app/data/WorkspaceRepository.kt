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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    /** Production constructor used by Hilt. */
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: WorkspaceCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
        cache,
        storeCache,
        offlineFlags,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

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
            key = "notes",
            encode = { m -> encModule { it["notes"] = arr(m.notes.map(WorkspaceRecordCodec::encodeNote)) } },
            merge = { m, plain -> m.copy(notes = records(plain, "notes").map(WorkspaceRecordCodec::decodeNote)) },
            changed = { a, b -> a.notes != b.notes },
        ),
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
            priorFilesRoot = FilesShardWriter.RootState(); return emptyList<FileEntry>() to emptyList()
        }
        if (res.code() == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthException()
        if (!res.isSuccessful) { priorFilesRoot = FilesShardWriter.RootState(); return emptyList<FileEntry>() to emptyList() }

        val body = res.body()!!
        filesVersion = body.version
        fileRawById.clear(); folderRawById.clear()
        val root = body.ciphertext?.let { ct ->
            val plain = crypto.openManifest(ct, vk) ?: throw DecryptException()
            filesJson.decodeFromString(FilesRoot.serializer(), plain)
        } ?: run { priorFilesRoot = FilesShardWriter.RootState(); return emptyList<FileEntry>() to emptyList() }

        priorFilesRoot = rootStateFrom(root)
        val files = if (root.shards.isNotEmpty()) {
            coroutineScope {
                root.shards.map { s ->
                    async {
                        val r = api.rawFile(s.ref)
                        check(r.isSuccessful) { "files shard ${s.ref}: http ${r.code()}" }
                        val bytes = BlobDownloader.decrypt(r.body()!!.bytes(), s.key, vk, crypto)
                        filesJson.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
                    }
                }.awaitAll().flatten()
            }.map { obj -> FileRecordCodec.decodeFile(obj).also { fileRawById[it.id] = obj } }
        } else {
            root.files
        }
        val folders = if (root.foldersRef != null) {
            val r = api.rawFile(root.foldersRef!!)
            check(r.isSuccessful) { "files folders ${root.foldersRef}: http ${r.code()}" }
            val bytes = BlobDownloader.decrypt(r.body()!!.bytes(), root.foldersKey ?: "", vk, crypto)
            filesJson.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
                .map { obj -> FileRecordCodec.decodeFolder(obj).also { folderRawById[it.id] = obj } }
        } else {
            emptyList()
        }
        return files to folders
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

    suspend fun load(): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        return try {
            val loaded = coroutineScope {
                val filesDeferred = async { loadFilesSlice(api, vk) }
                val mods = specs.map { spec -> async { spec to fetchModule(api, spec, vk) } }.awaitAll()
                mods to filesDeferred.await()
            }
            val (mods, filesSlice) = loaded

            var agg = WorkspaceManifest(v = 3)
            for ((spec, ml) in mods) {
                versions[spec.key] = ml.version
                if (ml.plain != null) agg = spec.merge(agg, ml.plain)
            }
            agg = agg.copy(files = filesSlice.first, fileFolders = filesSlice.second)
            Outcome.Ok(Workspace(agg, 0))
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
    suspend fun save(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)

        // Establish the aggregate base + per-module versions.
        var curBase = cache.value.value?.manifest
        if (curBase == null || versions.size < specs.size) {
            when (val l = load()) {
                is Outcome.Ok -> curBase = l.value.manifest
                is Outcome.Err -> return l
            }
        }

        var curNext = mutate(curBase!!)

        // Sharded /files/store slice: build the v3 root (files → shard blobs, folders →
        // collection blob, reusing unchanged blobs), seal + PUT with the shards[] guard. On
        // 409, reload the winning slice, re-apply mutate, retry — same loop shape as a module.
        if (curNext.files != curBase!!.files || curNext.fileFolders != curBase!!.fileFolders) {
            val writer = newFilesWriter(api, vk)
            var version = filesVersion
            var attempts = 0
            while (true) {
                if (attempts++ >= 5) return Outcome.Err(ErrorKind.HTTP)
                val result = writer.build(curNext.files, curNext.fileFolders, priorFilesRoot)
                    ?: return Outcome.Err(ErrorKind.NETWORK) // a shard/collection upload failed
                val rootCipher = crypto.sealManifest(CanonicalJson.encode(result.rootJson), vk)
                val put = try {
                    api.filesStorePut(StorePutRequest(rootCipher, version, result.shardRefs))
                } catch (e: Exception) {
                    return Outcome.Err(ErrorKind.NETWORK, e)
                }
                when {
                    put.isSuccessful -> {
                        filesVersion = put.body()?.version ?: (version + 1)
                        priorFilesRoot = result.state
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
                    else -> return Outcome.Err(ErrorKind.HTTP)
                }
            }
        }

        for (spec in specs) {
            if (!spec.changed(curBase!!, curNext)) continue
            var version = versions[spec.key] ?: 0
            var attempts = 0
            while (true) {
                if (attempts++ >= 4) return Outcome.Err(ErrorKind.HTTP)
                val ciphertext = crypto.sealManifest(spec.encode(curNext), vk)
                val put = try {
                    api.putModuleStore(spec.key, StorePutRequest(ciphertext, version))
                } catch (e: Exception) {
                    return Outcome.Err(ErrorKind.NETWORK, e)
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
                            return Outcome.Err(ErrorKind.NETWORK, e)
                        }
                        if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                        val body = res.body()!!
                        version = body.version
                        val freshPlain = body.ciphertext?.let {
                            crypto.openManifest(it, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        } ?: spec.emptyPlain()
                        curBase = spec.merge(curBase!!, freshPlain)
                        curNext = mutate(curBase!!)
                    }
                    else -> return Outcome.Err(ErrorKind.HTTP)
                }
            }
        }

        val result = Workspace(curNext, 0)
        cache.set(result)
        return Outcome.Ok(result)
    }
}
