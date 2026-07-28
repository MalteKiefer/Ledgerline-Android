package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.FinanceCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.FinanceManifest
import de.ledgerline.app.domain.model.FinanceStore
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the sealed sharded `/invoices/store` (invoices) + the non-secret `/company` profile.
 *
 * **Read-only for now.** Android does not yet WRITE the invoices store: the sharded root carries two
 * additional collection blobs (paymentMethods + transactions) that [ShardedStoreEngine] can't
 * preserve on save, so re-sealing the root would drop them. Until the multi-collection sharded write
 * lands, create/edit is disabled — never risk clobbering financial records. The load reuses the same
 * content-addressed engine as notes/passwords (invoices = the record shards; collections ignored).
 */
@Singleton
class FinanceRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: FinanceCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val blobCache: BlobDiskCache,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: FinanceCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        blobCache: BlobDiskCache,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags, blobCache,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    /** Read-only sharded engine bound to `/invoices/store`. Put/upload are never called (read-only). */
    private val engine: ShardedStoreEngine by lazy {
        ShardedStoreEngine(
            crypto = crypto,
            blobCache = blobCache,
            storeCache = storeCache,
            offlineFlags = offlineFlags,
            rootCacheKey = "invoices_root",
            storeGet = { api().invoicesStore() },
            storePut = { throw UnsupportedOperationException("finance is read-only") },
            rawBlob = { ref -> api().rawInvoice(ref) },
            uploadBlobApi = { throw UnsupportedOperationException("finance is read-only") },
        )
    }

    private fun api(): LedgerlineApi = apiProvider(sessionHolder.get()!!)

    /** Load invoices from the sharded store + the company profile. */
    suspend fun load(): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        try {
            val loaded = engine.load(vk)
            val invoices = loaded.records.mapNotNull(FinanceRecordCodec::decodeInvoice)
            val store = FinanceStore(FinanceManifest(invoices = invoices, seq = 0), engine.version)
            cache.set(store)
            loadCompany()
            Outcome.Ok(store)
        } catch (_: ShardedStoreEngine.AuthException) {
            Outcome.Err(ErrorKind.HTTP)
        } catch (e: Exception) {
            cache.value.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
        }
    }

    /** Best-effort company profile fetch (non-secret business identity for invoice display). */
    suspend fun loadCompany(): CompanyProfile? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).company()
            if (!res.isSuccessful) return null
            val c = res.body()?.let(FinanceRecordCodec::companyFrom) ?: return null
            cache.setCompany(c)
            c
        } catch (_: Exception) {
            null
        }
    }
}
