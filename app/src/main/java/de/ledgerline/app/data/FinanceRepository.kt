package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.FinanceCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.CanonicalJson
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.GallerySharding
import de.ledgerline.app.domain.model.PaymentMethod
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.RECOVERABLE_SAVE_ERRORS
import de.ledgerline.app.core.offline.StoreDelta
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.offline.SyncOutbox
import de.ledgerline.app.core.offline.SyncableStore
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.CompanyDto
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.FinanceManifest
import de.ledgerline.app.domain.model.FinanceStore
import de.ledgerline.app.domain.model.GalleryShard
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + WRITES the sealed sharded `/invoices/store` (invoices) + the non-secret `/company` profile.
 *
 * The invoices sharded root carries additional collection blobs (`paymentMethods`, `transactions`)
 * and inline keys (`partners`, `financeCategories`, `invoiceSeq`, …) that Android does not edit. So
 * the write path does a **root-level raw-overlay**: it re-shards ONLY the invoices and re-seals the
 * root with just `shardBits`/`shards` replaced — every other root key (the collection descriptors +
 * inline data) is carried through verbatim, and their blob refs are added to the `shards[]` guard so
 * the server never frees them. This is what makes create/edit safe without a payment-methods UI.
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
    private val connectivity: Connectivity,
    private val syncOutbox: SyncOutbox,
    private val apiProvider: (Session) -> LedgerlineApi,
) : SyncableStore {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: FinanceCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        blobCache: BlobDiskCache,
        connectivity: Connectivity,
        syncOutbox: SyncOutbox,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags, blobCache,
        connectivity, syncOutbox,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    override val syncLabel: String = "finance"

    private companion object {
        const val KEY = "invoices_root"
        const val COMPANY_KEY = "invoices_company"
        /** SyncOutbox key for offline finance write deltas (invoices + all 4 collections). */
        const val OUTBOX = "finance"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var version = 0
    // The last decoded root (all keys) so a save preserves collections + inline data verbatim.
    @Volatile private var priorRootRaw: JsonObject = JsonObject(emptyMap())
    // Prior invoice-shard descriptors for dirty-save reuse (unchanged shards aren't re-uploaded).
    @Volatile private var priorShards = SealedShardWriter.RootState()
    // True when a shard failed to decode on the last load → invoice-rebuilding writes must freeze.
    @Volatile private var degraded = false

    private fun api(): LedgerlineApi = apiProvider(sessionHolder.get()!!)

    // ---- load ----

    suspend fun load(): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Cache-first: show the last-cached store immediately, then refresh from the network below.
        // Without this the screen blocks on a full round-trip before anything appears.
        if (cache.value.value == null) runCatching { cachedOr(vk) }
        try {
            val res = api().invoicesStore()
            if (res.code() == HttpURLConnection.HTTP_UNAUTHORIZED) return@withContext Outcome.Err(ErrorKind.HTTP)
            if (!res.isSuccessful) return@withContext cachedOr(vk)
            val body = res.body()!!
            version = body.version
            val ct = body.ciphertext ?: run {
                priorRootRaw = JsonObject(emptyMap()); priorShards = SealedShardWriter.RootState()
                val store = FinanceStore(FinanceManifest(), version)
                cache.set(store); loadCompany(); return@withContext Outcome.Ok(store)
            }
            if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(ct, body.version))
            val plain = crypto.openManifest(ct, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
            val a = assemble(json.parseToJsonElement(plain).jsonObject, session, vk, allowNetwork = true)
            val store = FinanceStore(FinanceManifest(invoices = a.invoices, paymentMethods = a.paymentMethods, transactions = a.transactions, projects = a.projects, partners = a.partners, seq = 0), version)
            cache.set(store)
            loadCompany()
            Outcome.Ok(store)
        } catch (e: Exception) {
            cachedOr(vk, e)
        }
    }

    private data class Assembled(
        val invoices: List<Invoice>,
        val paymentMethods: List<de.ledgerline.app.domain.model.PaymentMethod>,
        val transactions: List<de.ledgerline.app.domain.model.Transaction>,
        val projects: List<de.ledgerline.app.domain.model.Project>,
        val partners: List<de.ledgerline.app.domain.model.Partner>,
    )

    /** Decode the root: capture its raw + shard descriptors, then fetch + decode shards + collections. */
    private suspend fun assemble(root: JsonObject, session: Session, vk: ByteArray, allowNetwork: Boolean): Assembled {
        priorRootRaw = root
        val shards = (root["shards"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::shardOf) }
        priorShards = SealedShardWriter.RootState(
            shardBits = root["shardBits"]?.jsonPrimitive?.intOrNull ?: 0,
            shards = shards,
            folders = null,
        )
        val api = apiProvider(session)
        val shardResults = if (shards.isEmpty()) emptyList() else coroutineScope {
            shards.map { s -> async(Dispatchers.IO) { fetchShard(api, s, vk, allowNetwork) } }.awaitAll()
        }
        // A null result = a shard we couldn't decode this load (durably 404 / offline-uncached).
        // Modifying + re-sharding the invoices now would DROP that shard's records, so freeze
        // invoice-rebuilding writes until the full set loads (collection-only writes stay safe).
        degraded = shardResults.any { it == null }
        val invoices = shardResults.flatMap { it ?: emptyList() }.mapNotNull(FinanceRecordCodec::decodeInvoice)

        val pm = fetchCollection(api, root, "payRef", "payKey", vk, allowNetwork)
            .mapNotNull(FinanceRecordCodec::decodePaymentMethod)
        val tx = fetchCollection(api, root, "txRef", "txKey", vk, allowNetwork)
            .mapNotNull(FinanceRecordCodec::decodeTransaction)
        val proj = fetchCollection(api, root, "projRef", "projKey", vk, allowNetwork)
            .mapNotNull(FinanceRecordCodec::decodeProject)
        val part = fetchCollection(api, root, "partRef", "partKey", vk, allowNetwork)
            .mapNotNull(FinanceRecordCodec::decodePartner)
        return Assembled(invoices, pm, tx, proj, part)
    }

    /**
     * Fetch + decrypt a named collection blob (`<name>Ref`/`<name>Key`) into its record array. A
     * missing/undecryptable blob degrades to empty (never crashes the load) — the ZK degraded-read
     * stance; the root's raw descriptor is still preserved so a later write re-references the blob.
     */
    private suspend fun fetchCollection(api: LedgerlineApi, root: JsonObject, refKey: String, keyKey: String, vk: ByteArray, allowNetwork: Boolean): List<JsonObject> {
        val ref = root[refKey]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val key = root[keyKey]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        try {
            val cipher = blobCache.get(ref) ?: run {
                if (!allowNetwork) return emptyList()
                val r = api.rawInvoice(ref)
                if (!r.isSuccessful) return emptyList()
                r.body()!!.bytes().also { if (offlineFlags.enabled()) blobCache.put(ref, it) }
            }
            val bytes = BlobDownloader.decrypt(cipher, key, vk, crypto)
            return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    private suspend fun fetchShard(api: LedgerlineApi, s: GalleryShard, vk: ByteArray, allowNetwork: Boolean): List<JsonObject>? {
        blobCache.get(s.ref)?.let { cipher ->
            val bytes = BlobDownloader.decrypt(cipher, s.key, vk, crypto)
            return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
        }
        if (!allowNetwork) return null
        var attempt = 0
        while (true) {
            val r = api.rawInvoice(s.ref)
            if (r.isSuccessful) {
                val cipher = r.body()!!.bytes()
                if (offlineFlags.enabled()) blobCache.put(s.ref, cipher)
                val bytes = BlobDownloader.decrypt(cipher, s.key, vk, crypto)
                return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
            }
            if (r.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                if (attempt < 3) { kotlinx.coroutines.delay(500L * (1 shl attempt)); attempt++; continue }
                return null // durably missing: skip (never drop-then-reconcile)
            }
            error("invoices shard ${s.ref}: http ${r.code()}")
        }
    }

    private suspend fun cachedOr(vk: ByteArray, e: Exception? = null): Outcome<FinanceStore> {
        val session = sessionHolder.get()
        if (offlineFlags.enabled() && session != null) {
            storeCache.get(KEY)?.let { env ->
                env.ciphertext?.let { ct ->
                    crypto.openManifest(ct, vk)?.let { plain ->
                        runCatching {
                            // Offline: assemble from cached shard blobs only (no network).
                            val a = assemble(json.parseToJsonElement(plain).jsonObject, session, vk, allowNetwork = false)
                            version = env.version
                            val store = FinanceStore(FinanceManifest(invoices = a.invoices, paymentMethods = a.paymentMethods, transactions = a.transactions, projects = a.projects, partners = a.partners), version)
                            cache.set(store)
                            loadCompanyCached(vk)
                            return Outcome.Ok(store)
                        }
                    }
                }
            }
        }
        return cache.value.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
    }

    // ---- save (invoices only; every other root key preserved) ----

    suspend fun save(queue: Boolean = true, mutate: (List<Invoice>) -> List<Invoice>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        // Refuse to re-shard invoices while a shard is missing — it would drop those records.
        if (degraded) return@withContext Outcome.Err(ErrorKind.HTTP)
        var next = mutate(base!!.manifest.invoices)
        // The intended manifest, computed from the ORIGINAL base — this is what we queue on any
        // offline/recoverable failure (a clean record-level delta that replays onto a later head).
        val myNext = base!!.manifest.copy(invoices = next)
        if (queue && !connectivity.isOnline()) return@withContext enqueueFinance(vk, base!!.manifest, myNext)
        val writer = SealedShardWriter { bytes, name -> uploadBytes(vk, bytes, name) }

        repeat(5) {
            val records = next.map { it.id to FinanceRecordCodec.encodeInvoice(it) }
            val result = writer.build(records, emptyList(), priorShards)
                ?: return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK) // a shard upload failed
            val rootJson = mergeRoot(priorRootRaw, result.rootJson)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = result.shardRefs + collectionRefs(priorRootRaw) + receiptBlobs(base!!.manifest.transactions)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    priorShards = result.state
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(base!!.manifest.copy(invoices = next), version)
                    cache.set(store)
                    return@withContext Outcome.Ok(store)
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    val fresh = when (val l = load()) {
                        is Outcome.Ok -> l.value.manifest.invoices
                        is Outcome.Err -> return@withContext if (queue && l.kind in RECOVERABLE_SAVE_ERRORS) enqueueFinance(vk, base!!.manifest, myNext) else l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
            }
        }
        recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
    }

    // ---- save payment methods (only the paymentMethods collection; every other root key preserved) ----

    /**
     * Re-seal the `paymentMethods` collection and PUT the root with only `payRef`/`payKey`/`payHash`
     * replaced — invoices' shards, `txRef`/`catRef` collections and inline `partners`/`invoiceSeq`
     * are carried through verbatim, and all their blob refs go into the `shards[]` guard so the
     * server never frees them. 409-rebase like the invoice save.
     */
    suspend fun savePaymentMethods(queue: Boolean = true, mutate: (List<PaymentMethod>) -> List<PaymentMethod>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        var next = mutate(base!!.manifest.paymentMethods)
        val myNext = base!!.manifest.copy(paymentMethods = next)
        if (queue && !connectivity.isOnline()) return@withContext enqueueFinance(vk, base!!.manifest, myNext)

        repeat(5) {
            val items = next.map(FinanceRecordCodec::encodePaymentMethod)
            val coll = sealCollection(vk, items, priorRootRaw["payRef"]?.jsonPrimitive?.contentOrNull, priorRootRaw["payHash"]?.jsonPrimitive?.contentOrNull, priorRootRaw["payKey"]?.jsonPrimitive?.contentOrNull)
                ?: return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK) // upload failed
            val root = priorRootRaw.toMutableMap()
            root["v"] = JsonPrimitive(3); root["suite"] = JsonPrimitive(1)
            if (coll.ref == null) {
                root.remove("payRef"); root.remove("payKey"); root.remove("payHash")
            } else {
                root["payRef"] = JsonPrimitive(coll.ref); root["payKey"] = JsonPrimitive(coll.key); root["payHash"] = JsonPrimitive(coll.hash)
            }
            val rootJson = JsonObject(root)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = priorShards.shards.map { it.ref } + collectionRefs(rootJson) + receiptBlobs(base!!.manifest.transactions)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(base!!.manifest.copy(paymentMethods = next), version)
                    cache.set(store)
                    return@withContext Outcome.Ok(store)
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    val fresh = when (val l = load()) {
                        is Outcome.Ok -> { base = l.value; l.value.manifest.paymentMethods }
                        is Outcome.Err -> return@withContext if (queue && l.kind in RECOVERABLE_SAVE_ERRORS) enqueueFinance(vk, base!!.manifest, myNext) else l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
            }
        }
        recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
    }

    /**
     * Re-seal the `transactions` collection and PUT the root with only `txRef`/`txKey`/`txHash`
     * replaced — everything else (invoices' shards, paymentMethods, financeCategories, inline data)
     * is preserved verbatim and guarded. 409-rebase. Mirrors [savePaymentMethods].
     */
    suspend fun saveTransactions(queue: Boolean = true, mutate: (List<de.ledgerline.app.domain.model.Transaction>) -> List<de.ledgerline.app.domain.model.Transaction>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        var next = mutate(base!!.manifest.transactions)
        val myNext = base!!.manifest.copy(transactions = next)
        if (queue && !connectivity.isOnline()) return@withContext enqueueFinance(vk, base!!.manifest, myNext)

        repeat(5) {
            val items = next.map(FinanceRecordCodec::encodeTransaction)
            val coll = sealCollection(vk, items, priorRootRaw["txRef"]?.jsonPrimitive?.contentOrNull, priorRootRaw["txHash"]?.jsonPrimitive?.contentOrNull, priorRootRaw["txKey"]?.jsonPrimitive?.contentOrNull)
                ?: return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            val root = priorRootRaw.toMutableMap()
            root["v"] = JsonPrimitive(3); root["suite"] = JsonPrimitive(1)
            if (coll.ref == null) {
                root.remove("txRef"); root.remove("txKey"); root.remove("txHash")
            } else {
                root["txRef"] = JsonPrimitive(coll.ref); root["txKey"] = JsonPrimitive(coll.key); root["txHash"] = JsonPrimitive(coll.hash)
            }
            val rootJson = JsonObject(root)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = priorShards.shards.map { it.ref } + collectionRefs(rootJson) + receiptBlobs(next)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(base!!.manifest.copy(transactions = next), version)
                    cache.set(store)
                    return@withContext Outcome.Ok(store)
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    val fresh = when (val l = load()) {
                        is Outcome.Ok -> { base = l.value; l.value.manifest.transactions }
                        is Outcome.Err -> return@withContext if (queue && l.kind in RECOVERABLE_SAVE_ERRORS) enqueueFinance(vk, base!!.manifest, myNext) else l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
            }
        }
        recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
    }

    // ---- statement import (transactions + optional invoice auto-match in ONE write) ----

    data class ImportResult(val added: Int, val matched: Int)

    /**
     * Import [parsed] statement lines into [accountId]: dedupe against the account's existing bookings,
     * create a `Transaction` per fresh line, and — when [matchInvoices] — auto-link each income line to
     * the issued invoice it settles (marking that invoice `paid` + writing `paymentTxId`, the tx gets
     * the `invoiceId`). Invoices AND the transactions collection are re-sealed in ONE root PUT so the
     * link is atomic; everything else (paymentMethods, financeCategories, inline data) is preserved and
     * guarded. 409-rebase re-derives dedupe + matches against the freshly loaded store.
     */
    suspend fun importStatement(accountId: String, parsed: List<de.ledgerline.app.core.finance.BankStatement.ParsedTx>, matchInvoices: Boolean): Outcome<ImportResult> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        val writer = SealedShardWriter { bytes, name -> uploadBytes(vk, bytes, name) }

        repeat(5) {
            val existing = base!!.manifest.transactions.filter { it.account == accountId && !it.trashed }
                .map { de.ledgerline.app.core.finance.BankStatement.ParsedTx(date = it.date, amount = it.amount, currency = it.currency, counterparty = it.counterparty, purpose = it.purpose) }
            val fresh = de.ledgerline.app.core.finance.BankStatement.dedupeTransactions(existing, parsed)
            if (fresh.isEmpty()) return@withContext Outcome.Ok(ImportResult(0, 0))

            // Only touch invoices when it's safe to re-shard the FULL set (not degraded).
            val canMatch = matchInvoices && !degraded
            val invoicesById = LinkedHashMap(base!!.manifest.invoices.associateBy { it.id })
            val newTxns = ArrayList<de.ledgerline.app.domain.model.Transaction>()
            var matched = 0
            for (p in fresh) {
                var tx = de.ledgerline.app.domain.model.Transaction(
                    id = de.ledgerline.app.core.Ids.newId(), account = accountId, date = p.date, amount = p.amount,
                    currency = p.currency, counterparty = p.counterparty, purpose = p.purpose,
                    vatCat = de.ledgerline.app.core.finance.BankStatement.guessVatCat(p),
                )
                if (canMatch) {
                    val hit = de.ledgerline.app.core.finance.InvoiceMatch.matchInvoice(p, invoicesById.values.toList())
                    if (hit != null) {
                        matched++
                        tx = tx.copy(invoiceId = hit.id)
                        val rawWithLink = JsonObject(hit.raw + ("paymentTxId" to JsonPrimitive(tx.id)))
                        invoicesById[hit.id] = hit.copy(status = de.ledgerline.app.domain.model.InvoiceStatus.PAID, raw = rawWithLink)
                    }
                }
                newTxns.add(tx)
            }
            val nextInvoices = invoicesById.values.toList()
            val nextTransactions = newTxns + base!!.manifest.transactions

            // Re-shard invoices ONLY when a match actually changed one; otherwise keep the prior shards
            // verbatim (a transactions-only write, always safe even when degraded).
            val built = if (matched > 0) writer.build(nextInvoices.map { it.id to FinanceRecordCodec.encodeInvoice(it) }, emptyList(), priorShards)
                ?: return@withContext Outcome.Err(ErrorKind.NETWORK) else null
            val txColl = sealCollection(vk, nextTransactions.map(FinanceRecordCodec::encodeTransaction), priorRootRaw["txRef"]?.jsonPrimitive?.contentOrNull, priorRootRaw["txHash"]?.jsonPrimitive?.contentOrNull, priorRootRaw["txKey"]?.jsonPrimitive?.contentOrNull)
                ?: return@withContext Outcome.Err(ErrorKind.NETWORK)

            val root = (if (built != null) mergeRoot(priorRootRaw, built.rootJson) else priorRootRaw).toMutableMap()
            root["v"] = JsonPrimitive(3); root["suite"] = JsonPrimitive(1)
            if (txColl.ref == null) { root.remove("txRef"); root.remove("txKey"); root.remove("txHash") }
            else { root["txRef"] = JsonPrimitive(txColl.ref); root["txKey"] = JsonPrimitive(txColl.key); root["txHash"] = JsonPrimitive(txColl.hash) }
            val rootJson = JsonObject(root)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = (built?.shardRefs ?: priorShards.shards.map { it.ref }) + collectionRefs(rootJson) + receiptBlobs(nextTransactions)
            val put = try { api().invoicesStorePut(StorePutRequest(rootCipher, version, guard)) } catch (e: Exception) { return@withContext Outcome.Err(ErrorKind.NETWORK, e) }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    if (built != null) priorShards = built.state
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(base!!.manifest.copy(invoices = nextInvoices, transactions = nextTransactions), version)
                    cache.set(store)
                    return@withContext Outcome.Ok(ImportResult(newTxns.size, matched))
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
                }
                else -> return@withContext Outcome.Err(ErrorKind.HTTP)
            }
        }
        Outcome.Err(ErrorKind.HTTP)
    }

    /**
     * Re-seal the `projects` collection and PUT the root with only `projRef`/`projKey`/`projHash`
     * replaced — everything else preserved + guarded, 409-rebase. Mirrors [savePaymentMethods].
     */
    suspend fun saveProjects(queue: Boolean = true, mutate: (List<de.ledgerline.app.domain.model.Project>) -> List<de.ledgerline.app.domain.model.Project>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        var next = mutate(base!!.manifest.projects)
        val myNext = base!!.manifest.copy(projects = next)
        if (queue && !connectivity.isOnline()) return@withContext enqueueFinance(vk, base!!.manifest, myNext)

        repeat(5) {
            val items = next.map(FinanceRecordCodec::encodeProject)
            val coll = sealCollection(vk, items, priorRootRaw["projRef"]?.jsonPrimitive?.contentOrNull, priorRootRaw["projHash"]?.jsonPrimitive?.contentOrNull, priorRootRaw["projKey"]?.jsonPrimitive?.contentOrNull)
                ?: return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            val root = priorRootRaw.toMutableMap()
            root["v"] = JsonPrimitive(3); root["suite"] = JsonPrimitive(1)
            if (coll.ref == null) { root.remove("projRef"); root.remove("projKey"); root.remove("projHash") }
            else { root["projRef"] = JsonPrimitive(coll.ref); root["projKey"] = JsonPrimitive(coll.key); root["projHash"] = JsonPrimitive(coll.hash) }
            val rootJson = JsonObject(root)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = priorShards.shards.map { it.ref } + collectionRefs(rootJson) + receiptBlobs(base!!.manifest.transactions)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(base!!.manifest.copy(projects = next), version)
                    cache.set(store)
                    return@withContext Outcome.Ok(store)
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    val fresh = when (val l = load()) {
                        is Outcome.Ok -> { base = l.value; l.value.manifest.projects }
                        is Outcome.Err -> return@withContext if (queue && l.kind in RECOVERABLE_SAVE_ERRORS) enqueueFinance(vk, base!!.manifest, myNext) else l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
            }
        }
        recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
    }

    /** Re-seal the `partners` collection (partRef) — everything else preserved + guarded, 409-rebase. */
    suspend fun savePartners(queue: Boolean = true, mutate: (List<de.ledgerline.app.domain.model.Partner>) -> List<de.ledgerline.app.domain.model.Partner>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        var next = mutate(base!!.manifest.partners)
        val myNext = base!!.manifest.copy(partners = next)
        if (queue && !connectivity.isOnline()) return@withContext enqueueFinance(vk, base!!.manifest, myNext)

        repeat(5) {
            val items = next.map(FinanceRecordCodec::encodePartner)
            val coll = sealCollection(vk, items, priorRootRaw["partRef"]?.jsonPrimitive?.contentOrNull, priorRootRaw["partHash"]?.jsonPrimitive?.contentOrNull, priorRootRaw["partKey"]?.jsonPrimitive?.contentOrNull)
                ?: return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            val root = priorRootRaw.toMutableMap()
            root["v"] = JsonPrimitive(3); root["suite"] = JsonPrimitive(1)
            if (coll.ref == null) { root.remove("partRef"); root.remove("partKey"); root.remove("partHash") }
            else { root["partRef"] = JsonPrimitive(coll.ref); root["partKey"] = JsonPrimitive(coll.key); root["partHash"] = JsonPrimitive(coll.hash) }
            val rootJson = JsonObject(root)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = priorShards.shards.map { it.ref } + collectionRefs(rootJson) + receiptBlobs(base!!.manifest.transactions)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.NETWORK)
            }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(base!!.manifest.copy(partners = next), version)
                    cache.set(store)
                    return@withContext Outcome.Ok(store)
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    val fresh = when (val l = load()) {
                        is Outcome.Ok -> { base = l.value; l.value.manifest.partners }
                        is Outcome.Err -> return@withContext if (queue && l.kind in RECOVERABLE_SAVE_ERRORS) enqueueFinance(vk, base!!.manifest, myNext) else l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
            }
        }
        recover(queue, vk, base!!.manifest, myNext, ErrorKind.HTTP)
    }

    /** Seal a collection array to a content blob; reuse the prior blob if the canonical bytes match. */
    private data class Coll(val ref: String?, val key: String?, val hash: String?)
    private suspend fun sealCollection(vk: ByteArray, items: List<JsonObject>, priorRef: String?, priorHash: String?, priorKey: String?): Coll? {
        if (items.isEmpty()) return Coll(null, null, null)
        val arr = JsonArray(items)
        val hash = GallerySharding.shardHash(arr)
        if (priorRef != null && priorHash == hash && priorKey != null) return Coll(priorRef, priorKey, hash)
        val blob = uploadBytes(vk, CanonicalJson.bytes(arr), "collection.enc") ?: return null
        return Coll(blob.id, blob.encFileKey, hash)
    }

    /** New root = prior root with only `shardBits`/`shards`/`caps` replaced; all else preserved. */
    private fun mergeRoot(prior: JsonObject, built: JsonObject): JsonObject {
        val out = prior.toMutableMap()
        out["v"] = JsonPrimitive(3)
        out["suite"] = JsonPrimitive(1)
        built["shardBits"]?.let { out["shardBits"] = it }
        built["shards"]?.let { out["shards"] = it }
        out["caps"] = built["caps"] ?: JsonObject(emptyMap())
        return JsonObject(out)
    }

    /**
     * EVERY collection blob ref the root points at, for the `shards[]` referential guard. The web
     * finance store declares collections `paymentMethods`/`transactions`/`partners`/`financeCategories`/
     * `projects` as `payRef`/`txRef`/`partRef`/`catRef`/`projRef` — and warns that a client which omits
     * ANY of them drops that collection's blob (server frees it as an orphan → data loss). We detect
     * them generically (any root key ending in `Ref` with a non-blank string value), so a NEW web
     * collection is guarded automatically even before Android has UI for it.
     */
    private fun collectionRefs(root: JsonObject): List<String> =
        root.entries.filter { it.key.endsWith("Ref") }
            .mapNotNull { (it.value as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

    /** Every receipt-document blob referenced inline by the transactions — must stay in the guard. */
    private fun receiptBlobs(txns: List<de.ledgerline.app.domain.model.Transaction>): List<String> =
        txns.flatMap { FinanceRecordCodec.decodeReceipts(it.raw).mapNotNull { r -> r.blob } }

    private suspend fun uploadBytes(vk: ByteArray, bytes: ByteArray, name: String): UploadedBlob? {
        val enc = crypto.newContentEncryptor(vk)
        val reqBody = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        return try {
            val part = MultipartBody.Part.createFormData("file", name, reqBody)
            val res = api().uploadInvoice(part)
            if (!res.isSuccessful) null else UploadedBlob(res.body()!!.id, enc.sealKey(), bytes.size.toLong())
        } catch (_: Exception) { null }
    }

    // ---- company ----

    suspend fun loadCompany(): CompanyProfile? {
        val session = sessionHolder.get() ?: return null
        val vk = vaultKeyHolder.get()
        return try {
            val res = apiProvider(session).company()
            if (!res.isSuccessful) return loadCompanyCached(vk)
            val dto = res.body()?.company ?: return loadCompanyCached(vk)
            val c = FinanceRecordCodec.companyFrom(dto)
            cache.setCompany(c)
            // Cache the (non-secret) company profile SEALED under VK so it's available offline.
            if (offlineFlags.enabled() && vk != null) runCatching {
                storeCache.put(COMPANY_KEY, StoreEnvelope(crypto.sealManifest(json.encodeToString(CompanyDto.serializer(), dto), vk), 0))
            }
            c
        } catch (_: Exception) { loadCompanyCached(vk) }
    }

    /** Restore the company profile from the sealed offline cache (transient error / offline). */
    private fun loadCompanyCached(vk: ByteArray?): CompanyProfile? {
        if (!offlineFlags.enabled() || vk == null) return cache.company.value
        return runCatching {
            storeCache.get(COMPANY_KEY)?.ciphertext?.let { ct ->
                crypto.openManifest(ct, vk)?.let { plain ->
                    FinanceRecordCodec.companyFrom(json.decodeFromString(CompanyDto.serializer(), plain)).also { cache.setCompany(it) }
                }
            }
        }.getOrNull() ?: cache.company.value
    }

    /** Fetch a bank/site favicon data-URI via the shared SSRF-guarded `/passwords/icon` proxy. */
    suspend fun fetchIcon(domain: String): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching { apiProvider(session).passwordsIcon(domain).body()?.icon }.getOrNull()
    }

    /** Fetch the stored company-logo image bytes (non-secret, streamed from `GET /company/logo`). */
    suspend fun companyLogo(): ByteArray? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching {
            val r = apiProvider(session).companyLogo()
            if (r.isSuccessful) r.body()?.bytes() else null
        }.getOrNull()
    }

    /** Encrypt + upload a receipt document → (blob id, sealed content key). Same blob format as files. */
    suspend fun uploadReceiptDocument(bytes: ByteArray): Pair<String, String>? = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext null
        uploadBytes(vk, bytes, "receipt.enc")?.let { it.id to it.encFileKey }
    }

    /**
     * Best-effort **server-side OCR** of a receipt (transient cleartext, ZK-parity with `/gallery/process`):
     * POSTs the raw bytes to `/invoices/ocr`, returns the line-structured text or null. Never stored; if
     * the endpoint is absent/errors, returns null and the caller falls back to manual entry.
     */
    suspend fun ocrDocument(bytes: ByteArray, name: String, mime: String): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching {
            val body = okhttp3.RequestBody.create(mime.toMediaTypeOrNull(), bytes)
            val part = MultipartBody.Part.createFormData("file", name, body)
            val r = apiProvider(session).invoicesOcr(part)
            if (r.isSuccessful) r.body()?.text?.takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    /** Fetch + decrypt a receipt document's bytes (in-memory; the caller renders it, no plaintext cache). */
    suspend fun downloadReceipt(blob: String, key: String): ByteArray? = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext null
        val cipher = blobCache.get(blob) ?: run {
            val r = try { api().rawInvoice(blob) } catch (_: Exception) { return@withContext null }
            if (!r.isSuccessful) return@withContext null
            r.body()!!.bytes().also { if (offlineFlags.enabled()) blobCache.put(blob, it) }
        }
        runCatching { BlobDownloader.decrypt(cipher, key, vk, crypto) }.getOrNull()
    }

    /** Update the non-secret company profile (`PUT /company`), then cache the server echo. */
    suspend fun saveCompany(profile: CompanyProfile): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        try {
            val res = apiProvider(session).companyPut(FinanceRecordCodec.companyToDto(profile))
            if (!res.isSuccessful) return@withContext false
            cache.setCompany(res.body()?.company?.let(FinanceRecordCodec::companyFrom) ?: profile)
            true
        } catch (_: Exception) { false }
    }

    /**
     * E-mail a finalized invoice as a PDF via the user's own invoice SMTP (POST /invoices/send). The
     * PDF is rendered on-device from the decrypted invoice + company profile (ZK — only the finished
     * PDF leaves, as an attachment). [to] defaults server-side to the customer's invoice e-mail.
     * Returns true on success (false includes 501 = invoice mail not configured).
     */
    suspend fun sendInvoice(inv: de.ledgerline.app.domain.model.Invoice, to: String?): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        val company = loadCompany() ?: CompanyProfile()
        val pdf = runCatching { de.ledgerline.app.core.finance.InvoicePdf.render(inv, company) }.getOrNull() ?: return@withContext false
        val recipient = (to?.ifBlank { null }) ?: inv.customer.email.ifBlank { null } ?: return@withContext false
        runCatching {
            val toBody = recipient.toRequestBody("text/plain".toMediaTypeOrNull())
            val part = okhttp3.MultipartBody.Part.createFormData("pdf", "invoice.pdf", pdf.toRequestBody("application/pdf".toMediaTypeOrNull()))
            apiProvider(session).invoicesSend(toBody, part).isSuccessful
        }.getOrDefault(false)
    }

    /** Send a sample test e-mail through the saved invoice SMTP (POST /invoices/mail-test). */
    suspend fun invoiceMailTest(): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        runCatching { apiProvider(session).invoicesMailTest().isSuccessful }.getOrDefault(false)
    }

    // ---- offline write outbox (record-level delta across invoices + the 4 collections) ----

    private fun collectionsOf(m: FinanceManifest): Map<String, Map<String, JsonObject>> = mapOf(
        "invoices" to m.invoices.associate { it.id to FinanceRecordCodec.encodeInvoice(it) },
        "paymentMethods" to m.paymentMethods.associate { it.id to FinanceRecordCodec.encodePaymentMethod(it) },
        "transactions" to m.transactions.associate { it.id to FinanceRecordCodec.encodeTransaction(it) },
        "projects" to m.projects.associate { it.id to FinanceRecordCodec.encodeProject(it) },
        "partners" to m.partners.associate { it.id to FinanceRecordCodec.encodePartner(it) },
    )

    /** Apply a queued collection delta onto a live list (upserts win, deletes drop; last-write-wins). */
    private fun <T> mergeRecords(
        list: List<T>,
        cd: de.ledgerline.app.core.offline.CollectionDelta?,
        id: (T) -> String,
        decode: (JsonObject) -> T?,
    ): List<T> {
        if (cd == null || cd.isEmpty) return list
        val byId = list.associateByTo(LinkedHashMap()) { id(it) }
        cd.deletes.forEach { byId.remove(it) }
        cd.upserts.forEach { (rid, obj) -> decode(obj)?.let { byId[rid] = it } }
        return byId.values.toList()
    }

    /**
     * Queue the base→next record delta to the durable, VK-sealed [SyncOutbox] and keep the optimistic
     * cache — so a finance edit made offline or during a transient server error is never silently lost;
     * [replayPending] replays it onto a later server head. Mirrors the other repositories' contract.
     */
    private fun recover(queue: Boolean, vk: ByteArray, base: FinanceManifest, next: FinanceManifest, kind: ErrorKind): Outcome<FinanceStore> =
        if (queue && kind in RECOVERABLE_SAVE_ERRORS) enqueueFinance(vk, base, next) else Outcome.Err(kind)

    private fun enqueueFinance(vk: ByteArray, base: FinanceManifest, next: FinanceManifest): Outcome<FinanceStore> {
        val delta = StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(OUTBOX, delta, vk)
        val store = FinanceStore(next, version)
        cache.set(store)
        return Outcome.Ok(store)
    }

    /**
     * Replay pending offline finance edits onto the current server head, one collection at a time via
     * the existing (byte-exact) online save paths with queuing disabled. Reloads the head first so each
     * save 409-rebases onto the winner. Invoice edits are skipped while degraded (a missing shard would
     * drop records) — the outbox is retained and retried once the full set loads. Clears on full drain.
     */
    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(OUTBOX, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(OUTBOX); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        if (load() is Outcome.Err) return@withContext false // fresh head into cache; 409 loops rebase on it
        var ok = true
        fun cd(k: String) = delta.collections[k]?.takeIf { !it.isEmpty }
        cd("invoices")?.let { c ->
            ok = if (degraded) false // can't safely re-shard now; keep the outbox and retry later
            else ok && save(queue = false) { list -> mergeRecords(list, c, { it.id }, FinanceRecordCodec::decodeInvoice) } is Outcome.Ok
        }
        cd("paymentMethods")?.let { c ->
            ok = ok && savePaymentMethods(queue = false) { list -> mergeRecords(list, c, { it.id }, FinanceRecordCodec::decodePaymentMethod) } is Outcome.Ok
        }
        cd("transactions")?.let { c ->
            ok = ok && saveTransactions(queue = false) { list -> mergeRecords(list, c, { it.id }, FinanceRecordCodec::decodeTransaction) } is Outcome.Ok
        }
        cd("projects")?.let { c ->
            ok = ok && saveProjects(queue = false) { list -> mergeRecords(list, c, { it.id }, FinanceRecordCodec::decodeProject) } is Outcome.Ok
        }
        cd("partners")?.let { c ->
            ok = ok && savePartners(queue = false) { list -> mergeRecords(list, c, { it.id }, FinanceRecordCodec::decodePartner) } is Outcome.Ok
        }
        if (ok) syncOutbox.clear(OUTBOX)
        ok
    }

    // ---- helpers ----

    private fun shardOf(o: JsonObject): GalleryShard? {
        val ref = o["ref"]?.jsonPrimitive?.contentOrNull ?: return null
        return GalleryShard(
            ref = ref,
            key = o["key"]?.jsonPrimitive?.contentOrNull ?: "",
            hash = o["hash"]?.jsonPrimitive?.contentOrNull,
            count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
            bucket = o["bucket"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
