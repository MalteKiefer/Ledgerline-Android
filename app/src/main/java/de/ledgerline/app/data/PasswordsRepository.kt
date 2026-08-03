package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.domain.model.SecretFolder
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.domain.model.SecretsManifest
import de.ledgerline.app.domain.model.SecretsStore
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + writes the sealed password-manager store. The web client migrated passwords off the
 * old single-blob monolith `GET/PUT /store/passwords` onto the **Store-v3 sharded** store
 * `GET/PUT /passwords/store` (records `secrets` bucketed into content-addressed shard blobs; a
 * single `secretFolders` collection blob addressed by the root's `foldersRef/foldersKey/
 * foldersHash`). Because the web BLANKS the monolith after migrating, an Android client that
 * still read the monolith showed an EMPTY vault for anyone who opened web/extension — hence this
 * port to [ShardedStoreEngine].
 *
 * Records flow through the engine as raw [JsonObject]s; [SecretRecordCodec] owns the typed
 * codec with a **raw-overlay** so unknown web/iOS top-level keys survive a round-trip (§15).
 * Optimistic concurrency: a 409 reloads the winning state, re-applies the mutation, retries.
 *
 * [load] also runs a **one-time dual-read migration**: when the sharded store is still empty and
 * the old monolith still carries records, it moves `secrets` + `secretFolders` into the sharded
 * store and blanks the monolith (`pwVaultMigrated`), byte-for-byte mirroring web `passwords.js`.
 */
@Singleton
class PasswordsRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: PasswordsCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val blobCache: BlobDiskCache,
    private val sharedVaults: SharedVaultRepository,
    private val syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val apiProvider: (Session) -> LedgerlineApi,
) : de.ledgerline.app.core.offline.SyncableStore {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: PasswordsCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        blobCache: BlobDiskCache,
        sharedVaults: SharedVaultRepository,
        syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
        connectivity: de.ledgerline.app.core.offline.Connectivity,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags, blobCache, sharedVaults,
        syncOutbox, connectivity,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        /** Old single-blob monolith module (migrated FROM). */
        const val MODULE = "passwords"
        /** Offline-cache key for the sharded root envelope (distinct from the old monolith key). */
        const val ROOT_KEY = "passwords_root"
        /** SyncOutbox key for offline write deltas. */
        const val OUTBOX = "passwords"
    }

    override val syncLabel: String = "passwords"

    // ---- Offline write outbox (record-level delta) ---------------------------
    /** The two collections this store owns, as id → record JSON (raw-overlay encoded). */
    private fun collectionsOf(m: SecretsManifest): Map<String, Map<String, JsonObject>> = mapOf(
        "secrets" to m.secrets.associate { it.id to SecretRecordCodec.encodeSecret(it, secretRawById[it.id]) },
        "secretFolders" to m.secretFolders.associate { it.id to SecretRecordCodec.encodeFolder(it, folderRawById[it.id]) },
    )

    /** Apply a record delta onto [m] (upsert overwrites/inserts by id, delete removes), capturing raws. */
    private fun applyDelta(m: SecretsManifest, delta: de.ledgerline.app.core.offline.StoreDelta): SecretsManifest {
        val sd = delta.collections["secrets"] ?: de.ledgerline.app.core.offline.CollectionDelta()
        val fd = delta.collections["secretFolders"] ?: de.ledgerline.app.core.offline.CollectionDelta()
        val byId = m.secrets.associateByTo(LinkedHashMap()) { it.id }
        sd.deletes.forEach { byId.remove(it) }
        sd.upserts.forEach { (id, obj) -> secretRawById[id] = obj; byId[id] = SecretRecordCodec.decodeSecret(obj) }
        val foldersById = m.secretFolders.associateByTo(LinkedHashMap()) { it.id }
        fd.deletes.forEach { foldersById.remove(it) }
        fd.upserts.forEach { (id, obj) -> folderRawById[id] = obj; foldersById[id] = SecretRecordCodec.decodeFolder(obj) }
        return m.copy(secrets = byId.values.toList(), secretFolders = foldersById.values.toList())
    }

    /** Layer any pending offline delta on top of [m] so the UI reflects un-synced local edits. */
    private fun withPending(m: SecretsManifest, vk: ByteArray): SecretsManifest =
        syncOutbox.pending(OUTBOX, vk)?.let { applyDelta(m, it) } ?: m

    /** Resolve the current session's API (session presence is guarded by load/save before use). */
    private fun api(): LedgerlineApi = apiProvider(sessionHolder.get()!!)

    /**
     * The reusable Store-v3 sharded engine bound to `/passwords/store`. Created once so its
     * version + prior-root state persist across load/save; each lambda resolves the current
     * session's API so a session swap is picked up.
     */
    private val engine: ShardedStoreEngine by lazy {
        ShardedStoreEngine(
            crypto = crypto,
            blobCache = blobCache,
            storeCache = storeCache,
            offlineFlags = offlineFlags,
            rootCacheKey = ROOT_KEY,
            storeGet = { api().passwordsStore() },
            storePut = { req -> api().passwordsStorePut(req) },
            rawBlob = { ref -> api().rawPassword(ref) },
            uploadBlobApi = { part -> api().uploadPassword(part) },
            reconcile = { refs -> api().passwordsReconcile(de.ledgerline.app.data.remote.dto.ReconcileRequest(refs)) },
            rawBatch = { refs -> api().passwordsRawBatch(de.ledgerline.app.data.remote.dto.ReconcileRequest(refs)) },
        )
    }

    // Raw record JSON captured on load so a save re-emits every foreign web/iOS field byte-exact
    // (no loss) — the raw-overlay strategy of [SecretRecordCodec] / [FileRecordCodec].
    private val secretRawById = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    private val folderRawById = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    /** Decode an engine load into typed records, (re)capturing each record's raw JSON. */
    private fun decodeLoaded(loaded: ShardedStoreEngine.Loaded): Pair<List<SecretItem>, List<SecretFolder>> {
        secretRawById.clear(); folderRawById.clear()
        val secrets = loaded.records.map { obj -> SecretRecordCodec.decodeSecret(obj).also { secretRawById[it.id] = obj } }
        val folders = loaded.folders.map { obj -> SecretRecordCodec.decodeFolder(obj).also { folderRawById[it.id] = obj } }
        return secrets to folders
    }

    /**
     * Recover records from a retained history-version sealed root [ciphertext] (`…/store/history/{v}`):
     * decode it (its shard blobs are content-addressed and usually still present), then re-add any
     * secret/folder whose id is MISSING from the current store (never overwrites a live record). Runs
     * through the normal 409-safe [save], so the recovered records are re-sharded + persisted. Returns
     * the number of records restored, or -1 if the version couldn't be decrypted/assembled.
     */
    suspend fun recoverFromHistoryRoot(ciphertext: String): Int = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext -1
        // Load current first (repopulates the raw maps for live records so save re-emits them intact).
        val cur = cache.value.value?.manifest ?: when (val l = load()) {
            is Outcome.Ok -> l.value.manifest
            is Outcome.Err -> return@withContext -1
        }
        val loaded = runCatching { engine.historyLoad(ciphertext, vk) }.getOrNull() ?: return@withContext -1
        val haveSecrets = cur.secrets.mapTo(HashSet()) { it.id }
        val haveFolders = cur.secretFolders.mapTo(HashSet()) { it.id }
        // Decode ONLY the missing old records, capturing their raw additively (never clobber a live id).
        val addSecrets = loaded.records.mapNotNull { obj ->
            val s = SecretRecordCodec.decodeSecret(obj)
            if (s.id in haveSecrets) null else { secretRawById[s.id] = obj; s }
        }
        val addFolders = loaded.folders.mapNotNull { obj ->
            val f = SecretRecordCodec.decodeFolder(obj)
            if (f.id in haveFolders) null else { folderRawById[f.id] = obj; f }
        }
        if (addSecrets.isEmpty() && addFolders.isEmpty()) return@withContext 0
        val out = save { m -> m.copy(secrets = m.secrets + addSecrets, secretFolders = m.secretFolders + addFolders) }
        if (out is Outcome.Ok) addSecrets.size + addFolders.size else -1
    }

    private fun encodeSecrets(m: SecretsManifest): List<Pair<String, JsonObject>> =
        m.secrets.map { it.id to SecretRecordCodec.encodeSecret(it, secretRawById[it.id]) }

    private fun encodeFolders(m: SecretsManifest): List<JsonObject> =
        m.secretFolders.map { SecretRecordCodec.encodeFolder(it, folderRawById[it.id]) }

    // Runs on Dispatchers.IO: opening sealed blobs (secretbox), JSON-decoding records, and the
    // disk-cache writes are CPU/IO-heavy and must not run on the caller's main thread (ANR).
    suspend fun load(): Outcome<SecretsStore> = withContext(Dispatchers.IO) {
        sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Cache-first: paint the last-cached secrets immediately (offline-assembled from the disk
        // cache) so the list shows before the network round-trip; engine.load below then refreshes.
        // Best-effort — a cold cache just falls through to the network load.
        if (cache.value.value == null) {
            runCatching {
                engine.loadCached(vk)?.let { l ->
                    val (s, f) = decodeLoaded(l)
                    cache.set(SecretsStore(withPending(SecretsManifest(secrets = s, secretFolders = f), vk), engine.version))
                }
            }
        }
        try {
            val loaded = engine.load(vk) // network-first; falls back to the offline cache internally
            var (secrets, folders) = decodeLoaded(loaded)
            // One-time migration off the old monolith while the sharded store is still empty.
            if (secrets.isEmpty() && folders.isEmpty()) {
                migrateFromMonolith(vk)?.let { (s, f) -> secrets = s; folders = f }
            }
            val store = SecretsStore(withPending(SecretsManifest(secrets = secrets, secretFolders = folders), vk), engine.version)
            cache.set(store)
            Outcome.Ok(store)
        } catch (_: ShardedStoreEngine.AuthException) {
            Outcome.Err(ErrorKind.HTTP) // 401 → forced-logout path, never fall back to cache
        } catch (e: Exception) {
            // Decrypt/decode failure inside the engine: never blank a vault that already had
            // content — prefer the last in-memory snapshot, else surface a network error.
            cache.value.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
        }
    }

    /**
     * One-time dual-read migration of the PERSONAL vault from the old single-blob monolith
     * (`/store/passwords`) to the sharded store, byte-mirroring web `migratePasswordsFromMonolith`.
     * Only runs while the sharded store is empty (the caller guards this) so a user who already
     * migrated on web can't re-import stale copies. Best-effort: any failure leaves the data in the
     * old store and returns null. Returns the moved (secrets, folders) on success.
     */
    private suspend fun migrateFromMonolith(vk: ByteArray): Pair<List<SecretItem>, List<SecretFolder>>? {
        val api = api()
        val res = try { api.moduleStore(MODULE) } catch (_: Exception) { return null }
        if (!res.isSuccessful) return null
        val body = res.body() ?: return null
        val ct = body.ciphertext ?: return null
        val plain = crypto.openManifest(ct, vk) ?: return null
        val (secrets, folders) = try { decodeMonolith(plain) } catch (_: Exception) { return null }
        if (secrets.isEmpty() && folders.isEmpty()) return null // nothing to move

        // Move into the sharded store (raw maps were populated by decodeMonolith → no field loss).
        val saved = commit(vk, SecretsManifest()) { m -> m.copy(secrets = secrets, secretFolders = folders) }
        if (saved !is Outcome.Ok) return null

        // Blank the monolith so a later delete-all can't re-import. Byte-shaped empty manifest,
        // exactly web `{ v:3, secrets:[], secretFolders:[], pwVaultMigrated:true }`.
        try {
            val empty = crypto.sealManifest(emptyMonolithJson(), vk)
            api.putModuleStore(MODULE, StorePutRequest(empty, body.version))
        } catch (_: Exception) { /* the empty-sharded guard still prevents re-import this session */ }

        return saved.value.manifest.secrets to saved.value.manifest.secretFolders
    }

    /** Decode the old monolith plaintext, capturing each record's raw JSON so a re-seal loses nothing. */
    private fun decodeMonolith(plain: String): Pair<List<SecretItem>, List<SecretFolder>> {
        val root = SecretRecordCodec.recordJson.parseToJsonElement(plain).jsonObject
        val secrets = (root["secrets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { obj -> SecretRecordCodec.decodeSecret(obj).also { secretRawById[it.id] = obj } }
        val folders = (root["secretFolders"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { obj -> SecretRecordCodec.decodeFolder(obj).also { folderRawById[it.id] = obj } }
        return secrets to folders
    }

    /** The byte-shaped empty monolith manifest (web parity): `{v:3,secrets:[],secretFolders:[],pwVaultMigrated:true}`. */
    private fun emptyMonolithJson(): String = buildJsonObject {
        put("v", 3)
        put("secrets", JsonArray(emptyList()))
        put("secretFolders", JsonArray(emptyList()))
        put("pwVaultMigrated", true)
    }.toString()

    /**
     * Token-only refresh of the offline cache: fetch the sharded **root** and write its ciphertext
     * to disk WITHOUT decrypting (no VK needed), so a background sync keeps the offline root
     * current while the vault is locked. Shard blobs are cached lazily on the next online load.
     * Ciphertext is opaque. No-op when offline caching is off / no session.
     */
    suspend fun refreshStoreCache(): Boolean {
        if (!offlineFlags.enabled()) return false
        val session = sessionHolder.get() ?: return false
        return try {
            val res = apiProvider(session).passwordsStore()
            if (!res.isSuccessful) return false
            val body = res.body() ?: return false
            storeCache.put(ROOT_KEY, StoreEnvelope(body.ciphertext, body.version))
            true
        } catch (_: Exception) { false }
    }

    /**
     * Server-assisted site favicon for [domain] → a data-URI string, or null. The server proxies
     * favicon/BIMI lookups so the client never contacts the third-party site directly (metadata
     * hygiene). Best-effort: any failure returns null (the UI falls back to the type icon).
     */
    suspend fun fetchIcon(domain: String): String? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).passwordsIcon(domain)
            if (!res.isSuccessful) null else res.body()?.icon?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /**
     * HIBP k-anonymity breach range for a 5-hex SHA-1 [prefix]: the server proxies the query so
     * only the prefix leaves the device (the full hash never does). Returns the raw range body
     * (`SUFFIX:count` lines) or null on failure. Match locally with [de.ledgerline.app.core.passwords.BreachCheck].
     */
    suspend fun breachRange(prefix: String): String? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).passwordsBreach(prefix)
            if (!res.isSuccessful) null else res.body()?.string()
        } catch (_: Exception) { null }
    }

    /** 2fa.directory dataset: `{ host → setup-docs URL }`. Empty on any failure. */
    suspend fun tfaDirectory(): Map<String, String> {
        val session = sessionHolder.get() ?: return emptyMap()
        return try {
            val res = apiProvider(session).passwordsTfaDirectory()
            if (!res.isSuccessful) emptyMap() else res.body()?.entries.orEmpty()
        } catch (_: Exception) { emptyMap() }
    }

    /** Optimistic write with 409-rebase (reload → re-apply [mutate] → retry). */
    // On Dispatchers.IO: sealing shard blobs + the root (CanonicalJson + secretstream/secretbox),
    // a 409 rebase decode, and disk-cache writes are heavy — never on the main thread.
    /**
     * Move [ids] into a new shared PASSWORD-vault: encode the selected secrets web-shaped (raw-overlay
     * preserved), create + seal the vault under a fresh VK_vault, then drop them from the personal
     * store. All-or-nothing — the personal store is only touched after the vault write succeeds.
     * Returns the new vault id, or null on failure. Trashed secrets are skipped.
     */
    suspend fun moveSecretsToSharedVault(name: String, ids: Set<String>): String? = withContext(Dispatchers.IO) {
        val base = cache.value.value?.manifest ?: when (val l = load()) {
            is Outcome.Ok -> l.value.manifest; is Outcome.Err -> return@withContext null
        }
        val moving = base.secrets.filter { it.id in ids && !it.isTrashed }
        if (moving.isEmpty()) return@withContext null
        val items = moving.map { SecretRecordCodec.encodeSecret(it, secretRawById[it.id]) }
        val vaultId = sharedVaults.createPasswordVault(name, items) ?: return@withContext null
        save { m -> m.copy(secrets = m.secrets.filterNot { it.id in ids }) }
        vaultId
    }

    suspend fun save(mutate: (SecretsManifest) -> SecretsManifest): Outcome<SecretsStore> = withContext(Dispatchers.IO) {
        sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Establish the base: the last in-memory manifest, else a fresh load (which also runs the
        // one-time migration and captures the raw maps).
        val base = cache.value.value?.manifest ?: when (val l = load()) {
            is Outcome.Ok -> l.value.manifest
            is Outcome.Err -> return@withContext l
        }
        commit(vk, base, mutate = mutate)
    }

    /**
     * The 409-rebase seal+PUT loop over the sharded engine (mirrors the files slice in
     * [WorkspaceRepository]). [base] is the known-current manifest; [mutate] is (re-)applied on
     * every attempt so a concurrent writer's changes are respected. One engine seal+PUT per turn.
     */
    private suspend fun commit(
        vk: ByteArray,
        base: SecretsManifest,
        allowOfflineQueue: Boolean = true,
        mutate: (SecretsManifest) -> SecretsManifest,
    ): Outcome<SecretsStore> {
        var curNext = mutate(base)
        // Offline: don't wait for a doomed PUT — persist the edit to the outbox + cache optimistically
        // (unless this is a replay, which must report failure so the outbox is retained).
        if (allowOfflineQueue && !connectivity.isOnline()) return enqueueOffline(vk, base, curNext)
        var attempts = 0
        while (true) {
            // Exhausted the 409-rebase retries (sustained write contention) → don't drop the edit:
            // queue it to the durable outbox to replay onto a later server head (replay path, which
            // passes allowOfflineQueue=false, still reports failure so its outbox is retained).
            if (attempts++ >= 5) return if (allowOfflineQueue) enqueueOffline(vk, base, curNext) else Outcome.Err(ErrorKind.HTTP)
            // DATA-LOSS FIX (same as the WorkspaceRepository slices): always rebase on the CURRENT
            // server root before sealing — `engine.version` is tracked separately from the cached
            // base, and if it drifts ahead a version-matched PUT would clobber the server's records
            // with the stale base + edit and NO 409. Reloading first makes the base == the exact
            // server state at that version, so the write is only ever additive (idempotent replay).
            val loaded = try {
                engine.load(vk)
            } catch (_: ShardedStoreEngine.AuthException) {
                return Outcome.Err(ErrorKind.HTTP)
            } catch (e: Exception) {
                return if (allowOfflineQueue) enqueueOffline(vk, base, curNext) else Outcome.Err(ErrorKind.NETWORK, e)
            }
            val (s0, f0) = decodeLoaded(loaded)
            curNext = mutate(SecretsManifest(secrets = s0, secretFolders = f0))
            val records = encodeSecrets(curNext)
            val folders = encodeFolders(curNext)
            when (val out = engine.sealAndPut(vk, records, folders, engine.version)) {
                is ShardedStoreEngine.PutOutcome.Ok -> {
                    val store = SecretsStore(curNext, out.newVersion)
                    cache.set(store)
                    return Outcome.Ok(store)
                }
                ShardedStoreEngine.PutOutcome.Conflict -> {
                    // Concurrent writer won the version race — loop re-fetches (rebases
                    // engine.version + priorRoot + raw maps) and re-applies mutate.
                    val loaded2 = try {
                        engine.load(vk)
                    } catch (_: ShardedStoreEngine.AuthException) {
                        return Outcome.Err(ErrorKind.HTTP)
                    } catch (e: Exception) {
                        return if (allowOfflineQueue) enqueueOffline(vk, base, curNext) else Outcome.Err(ErrorKind.NETWORK, e)
                    }
                    val (s, f) = decodeLoaded(loaded2)
                    curNext = mutate(SecretsManifest(secrets = s, secretFolders = f))
                }
                ShardedStoreEngine.PutOutcome.Error ->
                    return if (allowOfflineQueue) enqueueOffline(vk, base, curNext) else Outcome.Err(ErrorKind.NETWORK)
            }
        }
    }

    /** Persist an offline edit: record the [base]→[next] delta in the outbox and update the cache. */
    private fun enqueueOffline(vk: ByteArray, base: SecretsManifest, next: SecretsManifest): Outcome<SecretsStore> {
        val delta = de.ledgerline.app.core.offline.StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(OUTBOX, delta, vk)
        val store = SecretsStore(next, engine.version)
        cache.set(store)
        return Outcome.Ok(store) // optimistic — the edit is durable in the (VK-sealed) outbox
    }

    /** Replay pending offline password edits onto the current server head; clears the outbox on success. */
    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(OUTBOX, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(OUTBOX); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        // Push onto the CLEAN server head (not the delta-layered cache): load fresh, bake the delta
        // in via commit (whose 409 loop re-applies it onto the winning state), then clear the outbox.
        val clean = try {
            val loaded = engine.load(vk)
            var (s, f) = decodeLoaded(loaded)
            if (s.isEmpty() && f.isEmpty()) migrateFromMonolith(vk)?.let { (ss, ff) -> s = ss; f = ff }
            SecretsManifest(secrets = s, secretFolders = f)
        } catch (_: ShardedStoreEngine.AuthException) {
            return@withContext false
        } catch (_: Exception) {
            return@withContext false // still offline / transient — retry later
        }
        val out = commit(vk, clean, allowOfflineQueue = false) { m -> applyDelta(m, delta) }
        if (out is Outcome.Ok) { syncOutbox.clear(OUTBOX); true } else false
    }
}
