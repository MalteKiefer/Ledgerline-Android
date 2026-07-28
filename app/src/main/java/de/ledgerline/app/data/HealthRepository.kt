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
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: HealthCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "health"
        const val MODULE = "health"
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
                    } ?: HealthManifest()
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    val store = HealthStore(manifest, body.version)
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
            empty = { HealthManifest() },
            wrap = { m, v -> HealthStore(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
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
