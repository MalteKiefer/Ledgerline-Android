package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.ExploreCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.ExploreManifest
import de.ledgerline.app.domain.model.ExploreStore
import de.ledgerline.app.domain.model.ExploreTrackCodec
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + writes the sealed `store/explore` module (GPS tracks). Same optimistic-concurrency
 * envelope as the other modules: 409 → reload + re-apply the mutation. Tracks are opaque to the
 * server; foreign top-level keys (couplings/settings) and foreign per-track keys survive via the
 * raw-JSON overlay in [ExploreTrackCodec].
 */
@Singleton
class ExploreRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: ExploreCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: ExploreCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "explore"
        const val MODULE = "explore"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonEncoder = Json { encodeDefaults = true; explicitNulls = false }

    val cached: ExploreCache get() = cache

    private fun decodeManifest(plain: String): ExploreManifest {
        val root = json.parseToJsonElement(plain) as? JsonObject ?: JsonObject(emptyMap())
        return ExploreTrackCodec.decodeManifest(root)
    }

    private fun sealManifest(m: ExploreManifest, vk: ByteArray): String {
        val root = ExploreTrackCodec.encodeManifest(m)
        return crypto.sealManifest(jsonEncoder.encodeToString(JsonObject.serializer(), root), vk)
    }

    suspend fun load(): Outcome<ExploreStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        if (cache.value.value == null && offlineFlags.enabled()) {
            (cachedOr(Outcome.Err(ErrorKind.NETWORK), vk) as? Outcome.Ok)?.let { cache.set(it.value) }
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
                    } ?: ExploreManifest()
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    val store = ExploreStore(manifest, body.version)
                    cache.set(store)
                    Outcome.Ok(store)
                }
            }
        } catch (e: Exception) {
            cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk)
        }
    }

    suspend fun save(mutate: (ExploreManifest) -> ExploreManifest): Outcome<ExploreStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        val current = cache.value.value
        optimisticSave(
            cached = current?.let { it.manifest to it.version },
            mutate = mutate,
            fetch = { api.moduleStore(MODULE) },
            put = { api.putModuleStore(MODULE, it) },
            seal = { m -> sealManifest(m, vk) },
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { ExploreManifest() },
            wrap = { m, v -> ExploreStore(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
    }

    /** Reverse-geocode a coordinate to a place name via the server (coarse grid, never cached). */
    suspend fun reverse(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching { apiProvider(session).galleryReverse(lat, lng).body()?.place }.getOrNull()
    }

    /** Reverse-geocode to the full response (place + structured address parts). */
    suspend fun reverseAddress(lat: Double, lng: Double): de.ledgerline.app.data.remote.dto.ReverseResponse? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching { apiProvider(session).galleryReverse(lat, lng).body() }.getOrNull()
    }

    /** Snap waypoints to a routed path. Returns `[(lat,lng)…]` (null on failure / no route). */
    suspend fun route(waypoints: List<Pair<Double, Double>>): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext null
        val session = sessionHolder.get() ?: return@withContext null
        val points = waypoints.joinToString(";") { (la, ln) -> "${"%.6f".format(java.util.Locale.US, la)},${"%.6f".format(java.util.Locale.US, ln)}" }
        runCatching {
            apiProvider(session).mapsRoute(points).body()?.geometry
                ?.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }
                ?.takeIf { it.size >= 2 }
        }.getOrNull()
    }

    private fun cachedOr(err: Outcome<ExploreStore>, vk: ByteArray): Outcome<ExploreStore> =
        cachedOrStore(
            cachingEnabled = offlineFlags.enabled(),
            envelope = storeCache.get(KEY),
            err = err,
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { ExploreManifest() },
            wrap = { m, v -> ExploreStore(m, v) },
        )
}
