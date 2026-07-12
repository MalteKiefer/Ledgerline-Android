package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Loads + decrypts the workspace manifest over the pinned, authenticated session. */
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

    private companion object {
        const val KEY = "workspace"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonEncoder = Json { encodeDefaults = true }

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

    suspend fun load(): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        return try {
            val res = api.store()
            when {
                // 401 stays an auth failure → forced-logout path; never fall back to cache.
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> cachedOr(Outcome.Err(ErrorKind.NETWORK), vk)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        json.decodeFromString<WorkspaceManifest>(plain)
                    } ?: WorkspaceManifest()
                    if (offlineFlags.enabled()) {
                        storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    }
                    Outcome.Ok(Workspace(manifest, body.version))
                }
            }
        } catch (e: Exception) {
            cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk)
        }
    }

    /**
     * Network-first cache fallback: when a fetch fails with a non-auth error, try the
     * on-disk sealed envelope and decrypt it in-memory with [vk]. Returns [err]
     * unchanged if offline caching is off, no entry exists, or decryption fails.
     */
    private fun cachedOr(err: Outcome<Workspace>, vk: ByteArray): Outcome<Workspace> {
        if (!offlineFlags.enabled()) return err
        val env = storeCache.get(KEY) ?: return err
        return try {
            val manifest = env.ciphertext?.let { ct ->
                val plain = crypto.openManifest(ct, vk) ?: return err
                json.decodeFromString<WorkspaceManifest>(plain)
            } ?: WorkspaceManifest()
            Outcome.Ok(Workspace(manifest, env.version))
        } catch (_: Exception) {
            err
        }
    }

    /**
     * Token-only refresh of the offline cache: fetch the sealed `/store` envelope and
     * write its ciphertext to disk WITHOUT decrypting (no VK needed). Lets a background
     * sync keep the offline copy current even while the vault is locked — the ciphertext
     * is opaque, so nothing sensitive is exposed. No-op when offline caching is off, there
     * is no session, or the fetch fails.
     */
    suspend fun refreshStoreCache(): Boolean {
        if (!offlineFlags.enabled()) return false
        val session = sessionHolder.get() ?: return false
        return try {
            val res = apiProvider(session).store()
            if (!res.isSuccessful) return false
            val body = res.body() ?: return false
            storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Optimistic write: apply [mutate] to the current manifest, PUT it; on 409 reload
     * the server manifest, re-apply [mutate], and retry (bounded to 4 attempts).
     * Updates the cache on success.
     */
    suspend fun save(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)

        var base: WorkspaceManifest? = cache.value.value?.manifest
        var version: Int? = cache.value.value?.version

        repeat(4) {
            if (base == null || version == null) {
                val res = api.store()
                if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                val body = res.body()!!
                base = body.ciphertext?.let {
                    json.decodeFromString<WorkspaceManifest>(
                        crypto.openManifest(it, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                    )
                } ?: WorkspaceManifest()
                version = body.version
            }

            val next = mutate(base!!)
            val ciphertext = crypto.sealManifest(
                jsonEncoder.encodeToString(WorkspaceManifest.serializer(), next),
                vk,
            )
            val put = try {
                api.putStore(StorePutRequest(ciphertext, version!!))
            } catch (e: Exception) {
                return Outcome.Err(ErrorKind.NETWORK, e)
            }

            when {
                put.isSuccessful -> {
                    val newVersion = put.body()?.version ?: (version!! + 1)
                    val ws = Workspace(next, newVersion)
                    cache.set(ws)
                    if (offlineFlags.enabled()) {
                        storeCache.put(KEY, StoreEnvelope(ciphertext, newVersion))
                    }
                    return Outcome.Ok(ws)
                }
                put.code() == 409 -> {
                    // Reload fresh server state, then loop to re-apply mutate.
                    val res = api.store()
                    if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                    val body = res.body()!!
                    base = body.ciphertext?.let {
                        json.decodeFromString<WorkspaceManifest>(
                            crypto.openManifest(it, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        )
                    } ?: WorkspaceManifest()
                    version = body.version
                }
                else -> return Outcome.Err(ErrorKind.HTTP)
            }
        }
        return Outcome.Err(ErrorKind.HTTP) // gave up after retries
    }
}
