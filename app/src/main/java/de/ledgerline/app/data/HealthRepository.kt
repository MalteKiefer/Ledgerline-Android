package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.HealthCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.HealthManifest
import de.ledgerline.app.domain.model.HealthRecordCodec
import de.ledgerline.app.domain.model.HealthStore
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + writes the sealed `store/health` module (measurements, master data, fasting). Same
 * optimistic-concurrency envelope as the other monolith modules ([ExploreRepository] is the
 * template): 409 → reload + re-apply the mutation. Health data is highly sensitive and lives ONLY
 * in this sealed manifest — the server never sees plaintext. Foreign top-level keys and foreign
 * per-record keys survive via the raw-JSON overlay in [HealthRecordCodec].
 */
@Singleton
class HealthRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: HealthCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val apiProvider: (Session) -> LedgerlineApi,
) : de.ledgerline.app.core.offline.SyncableStore {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: HealthCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
        connectivity: de.ledgerline.app.core.offline.Connectivity,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags, syncOutbox, connectivity,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "health"
        const val MODULE = "health"
        val LIST_KEYS = listOf("healthEntries", "healthFasts")
        val SINGLETON_KEYS = listOf("healthProfile")
    }

    override val syncLabel: String = "health"

    // ---- Offline write outbox (record-level delta over the manifest root) ----
    private fun collectionsOf(m: HealthManifest) =
        de.ledgerline.app.core.offline.ManifestDelta.collections(HealthRecordCodec.encodeManifest(m), LIST_KEYS, SINGLETON_KEYS)

    private fun applyDelta(m: HealthManifest, delta: de.ledgerline.app.core.offline.StoreDelta): HealthManifest =
        HealthRecordCodec.decodeManifest(
            de.ledgerline.app.core.offline.ManifestDelta.apply(HealthRecordCodec.encodeManifest(m), delta, LIST_KEYS, SINGLETON_KEYS),
        )

    private fun withPending(m: HealthManifest, vk: ByteArray): HealthManifest =
        syncOutbox.pending(KEY, vk)?.let { applyDelta(m, it) } ?: m

    private fun enqueueOffline(vk: ByteArray, base: HealthManifest, next: HealthManifest): Outcome<HealthStore> {
        val delta = de.ledgerline.app.core.offline.StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(KEY, delta, vk)
        val store = HealthStore(next, cache.value.value?.version ?: 0)
        cache.set(store)
        return Outcome.Ok(store)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonEncoder = Json { encodeDefaults = true; explicitNulls = false }

    val cached: HealthCache get() = cache

    private fun decodeManifest(plain: String): HealthManifest {
        val root = json.parseToJsonElement(plain) as? JsonObject ?: JsonObject(emptyMap())
        return HealthRecordCodec.decodeManifest(root)
    }

    private fun sealManifest(m: HealthManifest, vk: ByteArray): String {
        val root = HealthRecordCodec.encodeManifest(m)
        return crypto.sealManifest(jsonEncoder.encodeToString(JsonObject.serializer(), root), vk)
    }

    suspend fun load(): Outcome<HealthStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        if (cache.value.value == null && offlineFlags.enabled()) {
            (cachedOr(Outcome.Err(ErrorKind.NETWORK), vk) as? Outcome.Ok)?.let { cache.set(HealthStore(withPending(it.value.manifest, vk), it.value.version)) }
        }
        try {
            val res = api.moduleStore(MODULE)
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> cachedOr(Outcome.Err(ErrorKind.NETWORK), vk)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
                        decodeManifest(plain)
                    } ?: HealthManifest()
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    // Layer any un-synced offline edits on top of the fresh server state.
                    val store = HealthStore(withPending(manifest, vk), body.version)
                    cache.set(store)
                    Outcome.Ok(store)
                }
            }
        } catch (e: Exception) {
            cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk)
        }
    }

    suspend fun save(mutate: (HealthManifest) -> HealthManifest): Outcome<HealthStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Resolve the base manifest (needed to compute an offline delta).
        val base = cache.value.value?.manifest
            ?: (load() as? Outcome.Ok)?.value?.manifest
            ?: HealthManifest()
        // Offline: queue the edit + optimistic cache instead of a doomed PUT.
        if (!connectivity.isOnline()) return@withContext enqueueOffline(vk, base, mutate(base))
        val out = pushOptimistic(session, vk, cache.value.value?.let { it.manifest to it.version }, mutate)
        // Connection dropped mid-flight → fall back to the outbox rather than losing the edit.
        if (out is Outcome.Err && out.kind == ErrorKind.NETWORK) enqueueOffline(vk, base, mutate(base)) else out
    }

    /** The raw optimistic seal+PUT (no offline handling) — shared by [save] and [replayPending]. */
    private suspend fun pushOptimistic(
        session: Session,
        vk: ByteArray,
        cached: Pair<HealthManifest, Int>?,
        mutate: (HealthManifest) -> HealthManifest,
    ): Outcome<HealthStore> {
        val api = apiProvider(session)
        return optimisticSave(
            cached = cached,
            mutate = mutate,
            fetch = { api.moduleStore(MODULE) },
            put = { api.putModuleStore(MODULE, it) },
            seal = { m -> sealManifest(m, vk) },
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { HealthManifest() },
            wrap = { m, v -> HealthStore(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
    }

    /** Replay pending offline health edits onto the current server head; clears the outbox on success. */
    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(KEY, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(KEY); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        // cached=null forces a fresh server fetch; the 409 loop re-applies the delta onto the winner.
        val out = pushOptimistic(session, vk, cached = null) { m -> applyDelta(m, delta) }
        if (out is Outcome.Ok) { syncOutbox.clear(KEY); true } else false
    }

    private fun cachedOr(err: Outcome<HealthStore>, vk: ByteArray): Outcome<HealthStore> =
        cachedOrStore(
            cachingEnabled = offlineFlags.enabled(),
            envelope = storeCache.get(KEY),
            err = err,
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { HealthManifest() },
            wrap = { m, v -> HealthStore(m, v) },
        )
}
