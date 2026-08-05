package de.ledgerline.app.data

import de.ledgerline.app.core.CalendarCache
import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.CalendarManifest
import de.ledgerline.app.domain.model.CalendarRecordCodec
import de.ledgerline.app.domain.model.CalendarStore
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + writes the sealed `store/calendar` module (calendars + events). Same optimistic-
 * concurrency envelope as the other monolith modules ([HealthRepository] is the template):
 * 409 → reload + re-apply the mutation. Zero-knowledge — the server never sees event times or
 * content. Foreign top-level keys, the `settings` feed overlay, and foreign per-record keys
 * survive via the raw-JSON overlay in [CalendarRecordCodec].
 */
@Singleton
class CalendarRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: CalendarCache,
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
        cache: CalendarCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
        connectivity: de.ledgerline.app.core.offline.Connectivity,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags, syncOutbox, connectivity,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "calendar"
        const val MODULE = "calendar"
        val LIST_KEYS = listOf("calendars", "events")
        val SINGLETON_KEYS = listOf("settings")
    }

    override val syncLabel: String = "calendar"

    private fun collectionsOf(m: CalendarManifest) =
        de.ledgerline.app.core.offline.ManifestDelta.collections(CalendarRecordCodec.encodeManifest(m), LIST_KEYS, SINGLETON_KEYS)

    private fun applyDelta(m: CalendarManifest, delta: de.ledgerline.app.core.offline.StoreDelta): CalendarManifest =
        CalendarRecordCodec.decodeManifest(
            de.ledgerline.app.core.offline.ManifestDelta.apply(CalendarRecordCodec.encodeManifest(m), delta, LIST_KEYS, SINGLETON_KEYS),
        )

    private fun withPending(m: CalendarManifest, vk: ByteArray): CalendarManifest =
        syncOutbox.pending(KEY, vk)?.let { applyDelta(m, it) } ?: m

    private fun enqueueOffline(vk: ByteArray, base: CalendarManifest, next: CalendarManifest): Outcome<CalendarStore> {
        val delta = de.ledgerline.app.core.offline.StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(KEY, delta, vk)
        val store = CalendarStore(next, cache.value.value?.version ?: 0)
        cache.set(store)
        return Outcome.Ok(store)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonEncoder = Json { encodeDefaults = true; explicitNulls = false }

    val cached: CalendarCache get() = cache

    private fun decodeManifest(plain: String): CalendarManifest {
        val root = json.parseToJsonElement(plain) as? JsonObject ?: JsonObject(emptyMap())
        return CalendarRecordCodec.decodeManifest(root)
    }

    private fun sealManifest(m: CalendarManifest, vk: ByteArray): String {
        val root = CalendarRecordCodec.encodeManifest(m)
        return crypto.sealManifest(jsonEncoder.encodeToString(JsonObject.serializer(), root), vk)
    }

    suspend fun load(): Outcome<CalendarStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        if (cache.value.value == null && offlineFlags.enabled()) {
            (cachedOr(Outcome.Err(ErrorKind.NETWORK), vk) as? Outcome.Ok)?.let { cache.set(CalendarStore(withPending(it.value.manifest, vk), it.value.version)) }
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
                    } ?: CalendarManifest()
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    val store = CalendarStore(withPending(manifest, vk), body.version)
                    cache.set(store)
                    Outcome.Ok(store)
                }
            }
        } catch (e: Exception) {
            cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk)
        }
    }

    suspend fun save(mutate: (CalendarManifest) -> CalendarManifest): Outcome<CalendarStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val base = cache.value.value?.manifest
            ?: (load() as? Outcome.Ok)?.value?.manifest
            ?: CalendarManifest()
        if (!connectivity.isOnline()) return@withContext enqueueOffline(vk, base, mutate(base))
        val out = pushOptimistic(session, vk, cache.value.value?.let { it.manifest to it.version }, mutate)
        if (out is Outcome.Err && out.kind in de.ledgerline.app.core.offline.RECOVERABLE_SAVE_ERRORS) enqueueOffline(vk, base, mutate(base)) else out
    }

    private suspend fun pushOptimistic(
        session: Session,
        vk: ByteArray,
        cached: Pair<CalendarManifest, Int>?,
        mutate: (CalendarManifest) -> CalendarManifest,
    ): Outcome<CalendarStore> {
        val api = apiProvider(session)
        return optimisticSave(
            cached = cached,
            mutate = mutate,
            fetch = { api.moduleStore(MODULE) },
            put = { api.putModuleStore(MODULE, it) },
            seal = { m -> sealManifest(m, vk) },
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { CalendarManifest() },
            wrap = { m, v -> CalendarStore(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
    }

    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(KEY, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(KEY); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        val out = pushOptimistic(session, vk, cached = null) { m -> applyDelta(m, delta) }
        if (out is Outcome.Ok) { syncOutbox.clear(KEY); true } else false
    }

    private fun cachedOr(err: Outcome<CalendarStore>, vk: ByteArray): Outcome<CalendarStore> =
        cachedOrStore(
            cachingEnabled = offlineFlags.enabled(),
            envelope = storeCache.get(KEY),
            err = err,
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { CalendarManifest() },
            wrap = { m, v -> CalendarStore(m, v) },
        )
}
