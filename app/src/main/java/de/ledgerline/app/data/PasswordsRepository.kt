package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.SecretsManifest
import de.ledgerline.app.domain.model.SecretsStore
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + writes the sealed password-manager store (`GET/PUT /api/v1/store/passwords`, Store v3
 * per-module). Same optimistic-concurrency envelope as the workspace modules: 409 → reload +
 * re-apply the mutation (last-write-wins per field). Secrets are opaque to the server; all
 * crypto is client-side. The type-specific `fields` are kept as raw JSON so unknown keys survive.
 */
@Singleton
class PasswordsRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: PasswordsCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: PasswordsCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "passwords"
        const val MODULE = "passwords"
        /** Top-level manifest keys this repo owns; everything else is preserved verbatim. */
        val KNOWN_KEYS = setOf("v", "secrets", "secretFolders", "pwVaultMigrated")
    }

    // Lenient + coercing: tolerate unknown keys, and coerce a JSON null on a non-null
    // defaulted field (e.g. tags:null, folder present-but-null) to its default instead of
    // throwing — web-written records vary and must never fail the whole decode.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val jsonEncoder = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * Parse the sealed plaintext into a typed manifest while capturing every foreign top-level
     * key into [SecretsManifest.extra] so a later save preserves it (iOS parity, §15).
     */
    private fun decodeManifest(plain: String): SecretsManifest {
        val root = json.parseToJsonElement(plain).jsonObject
        val typed = json.decodeFromJsonElement(SecretsManifest.serializer(), root)
        return typed.copy(extra = JsonObject(root.filterKeys { it !in KNOWN_KEYS }))
    }

    /** Seal a manifest, re-emitting the captured foreign keys alongside our owned keys. */
    private fun sealManifest(m: SecretsManifest, vk: ByteArray): String {
        val owned = jsonEncoder.encodeToJsonElement(SecretsManifest.serializer(), m).jsonObject
        val merged = JsonObject(m.extra + owned) // owned keys win on any collision
        return crypto.sealManifest(jsonEncoder.encodeToString(JsonObject.serializer(), merged), vk)
    }

    // Runs on Dispatchers.IO: opening the sealed manifest (secretbox), JSON-decoding the records,
    // and writing the ciphertext to the disk cache (kotlinx encode of a large base64 string) are all
    // CPU/IO-heavy and MUST NOT run on the caller's main thread — otherwise a large store ANRs.
    suspend fun load(): Outcome<SecretsStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        // Offline-first: publish the disk-cached store immediately (instant display at app start,
        // and the only source when offline), then refresh from the network below. This makes the
        // password list appear on unlock without waiting for a round-trip / pull-to-refresh.
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
                    } ?: SecretsManifest()
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    val store = SecretsStore(manifest, body.version)
                    cache.set(store)
                    Outcome.Ok(store)
                }
            }
        } catch (e: Exception) {
            cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk)
        }
    }

    /**
     * Token-only refresh of the offline cache: fetch the sealed store and write its ciphertext to
     * disk WITHOUT decrypting (no VK needed), so a background sync keeps the offline copy current
     * while the vault is locked. The ciphertext is opaque. No-op when offline caching is off / no
     * session. Returns true on a successful refresh.
     */
    suspend fun refreshStoreCache(): Boolean {
        if (!offlineFlags.enabled()) return false
        val session = sessionHolder.get() ?: return false
        return try {
            val res = apiProvider(session).moduleStore(MODULE)
            if (!res.isSuccessful) return false
            val body = res.body() ?: return false
            storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
            true
        } catch (_: Exception) { false }
    }

    private fun cachedOr(err: Outcome<SecretsStore>, vk: ByteArray): Outcome<SecretsStore> =
        cachedOrStore(
            cachingEnabled = offlineFlags.enabled(),
            envelope = storeCache.get(KEY),
            err = err,
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { SecretsManifest() },
            wrap = { m, v -> SecretsStore(m, v) },
        )

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

    /** Optimistic write with 409-merge (reload → re-apply [mutate] → retry). */
    // On Dispatchers.IO: sealing the manifest (CanonicalJson + secretbox over the whole store),
    // JSON decode on a 409 rebase, and the disk-cache write are heavy — never on the main thread.
    suspend fun save(mutate: (SecretsManifest) -> SecretsManifest): Outcome<SecretsStore> = withContext(Dispatchers.IO) {
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
            empty = { SecretsManifest() },
            wrap = { m, v -> SecretsStore(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
    }
}
