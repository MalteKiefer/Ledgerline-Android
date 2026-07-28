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
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
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

    private companion object {
        const val KEY = "invoices_root"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var version = 0
    // The last decoded root (all keys) so a save preserves collections + inline data verbatim.
    @Volatile private var priorRootRaw: JsonObject = JsonObject(emptyMap())
    // Prior invoice-shard descriptors for dirty-save reuse (unchanged shards aren't re-uploaded).
    @Volatile private var priorShards = SealedShardWriter.RootState()

    private fun api(): LedgerlineApi = apiProvider(sessionHolder.get()!!)

    // ---- load ----

    suspend fun load(): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
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
            val store = FinanceStore(FinanceManifest(invoices = a.invoices, paymentMethods = a.paymentMethods, transactions = a.transactions, seq = 0), version)
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
        val invoices = if (shards.isEmpty()) emptyList() else coroutineScope {
            shards.map { s -> async(Dispatchers.IO) { fetchShard(api, s, vk, allowNetwork) } }.awaitAll()
        }.flatMap { it ?: emptyList() }.mapNotNull(FinanceRecordCodec::decodeInvoice)

        val pm = fetchCollection(api, root, "payRef", "payKey", vk, allowNetwork)
            .mapNotNull(FinanceRecordCodec::decodePaymentMethod)
        val tx = fetchCollection(api, root, "txRef", "txKey", vk, allowNetwork)
            .mapNotNull(FinanceRecordCodec::decodeTransaction)
        return Assembled(invoices, pm, tx)
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
                            val store = FinanceStore(FinanceManifest(invoices = a.invoices, paymentMethods = a.paymentMethods, transactions = a.transactions), version)
                            cache.set(store)
                            return Outcome.Ok(store)
                        }
                    }
                }
            }
        }
        return cache.value.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
    }

    // ---- save (invoices only; every other root key preserved) ----

    suspend fun save(mutate: (List<Invoice>) -> List<Invoice>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        var next = mutate(base!!.manifest.invoices)
        val writer = SealedShardWriter { bytes, name -> uploadBytes(vk, bytes, name) }

        repeat(5) {
            val records = next.map { it.id to FinanceRecordCodec.encodeInvoice(it) }
            val result = writer.build(records, emptyList(), priorShards)
                ?: return@withContext Outcome.Err(ErrorKind.NETWORK) // a shard upload failed
            val rootJson = mergeRoot(priorRootRaw, result.rootJson)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = result.shardRefs + collectionRefs(priorRootRaw)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
            }
            when {
                put.isSuccessful -> {
                    version = put.body()?.version ?: (version + 1)
                    priorShards = result.state
                    priorRootRaw = rootJson
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, version))
                    val store = FinanceStore(
                        FinanceManifest(invoices = next, paymentMethods = base!!.manifest.paymentMethods, transactions = base!!.manifest.transactions),
                        version,
                    )
                    cache.set(store)
                    return@withContext Outcome.Ok(store)
                }
                put.code() == HttpURLConnection.HTTP_CONFLICT -> {
                    val fresh = when (val l = load()) {
                        is Outcome.Ok -> l.value.manifest.invoices
                        is Outcome.Err -> return@withContext l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext Outcome.Err(ErrorKind.HTTP)
            }
        }
        Outcome.Err(ErrorKind.HTTP)
    }

    // ---- save payment methods (only the paymentMethods collection; every other root key preserved) ----

    /**
     * Re-seal the `paymentMethods` collection and PUT the root with only `payRef`/`payKey`/`payHash`
     * replaced — invoices' shards, `txRef`/`catRef` collections and inline `partners`/`invoiceSeq`
     * are carried through verbatim, and all their blob refs go into the `shards[]` guard so the
     * server never frees them. 409-rebase like the invoice save.
     */
    suspend fun savePaymentMethods(mutate: (List<PaymentMethod>) -> List<PaymentMethod>): Outcome<FinanceStore> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        var base = cache.value.value
        if (base == null) {
            when (val l = load()) { is Outcome.Ok -> base = l.value; is Outcome.Err -> return@withContext l }
        }
        var next = mutate(base!!.manifest.paymentMethods)

        repeat(5) {
            val items = next.map(FinanceRecordCodec::encodePaymentMethod)
            val coll = sealCollection(vk, items, priorRootRaw["payRef"]?.jsonPrimitive?.contentOrNull, priorRootRaw["payHash"]?.jsonPrimitive?.contentOrNull, priorRootRaw["payKey"]?.jsonPrimitive?.contentOrNull)
                ?: return@withContext Outcome.Err(ErrorKind.NETWORK) // upload failed
            val root = priorRootRaw.toMutableMap()
            root["v"] = JsonPrimitive(3); root["suite"] = JsonPrimitive(1)
            if (coll.ref == null) {
                root.remove("payRef"); root.remove("payKey"); root.remove("payHash")
            } else {
                root["payRef"] = JsonPrimitive(coll.ref); root["payKey"] = JsonPrimitive(coll.key); root["payHash"] = JsonPrimitive(coll.hash)
            }
            val rootJson = JsonObject(root)
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(rootJson), vk)
            val guard = priorShards.shards.map { it.ref } + collectionRefs(rootJson)
            val put = try {
                api().invoicesStorePut(StorePutRequest(rootCipher, version, guard))
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
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
                        is Outcome.Err -> return@withContext l
                    }
                    next = mutate(fresh)
                }
                else -> return@withContext Outcome.Err(ErrorKind.HTTP)
            }
        }
        Outcome.Err(ErrorKind.HTTP)
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

    /** The preserved collection blob refs (paymentMethods/transactions/financeCategories) for the guard. */
    private fun collectionRefs(root: JsonObject): List<String> =
        listOf("payRef", "txRef", "catRef").mapNotNull { root[it]?.jsonPrimitive?.contentOrNull }

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
        return try {
            val res = apiProvider(session).company()
            if (!res.isSuccessful) return null
            val c = res.body()?.company?.let(FinanceRecordCodec::companyFrom) ?: return null
            cache.setCompany(c)
            c
        } catch (_: Exception) { null }
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
