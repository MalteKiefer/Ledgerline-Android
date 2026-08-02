package de.ledgerline.app.data

import de.ledgerline.app.core.FinanceCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.dto.CompanyDto
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.data.remote.dto.UploadResponse
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The data-loss-critical guarantee: writing an invoice must PRESERVE the sharded root's other
 * collection blobs (paymentMethods `payRef`, transactions `txRef`) + inline keys (`partners`) and
 * carry their blob refs in the `shards[]` guard, so the server never frees them.
 */
class FinanceRepositoryTest {

    private val crypto = object : Crypto {
        override fun sealManifest(json: String, vk: ByteArray) = "SEALED:$json"
        override fun openManifest(ciphertext: String, vk: ByteArray) = ciphertext.removePrefix("SEALED:")
        override fun sealValue(data: ByteArray, key: ByteArray) = "V:" + String(data, Charsets.ISO_8859_1)
        override fun openValue(cn: String, key: ByteArray) = cn.removePrefix("V:").toByteArray(Charsets.ISO_8859_1)
        override fun genericHash(input: ByteArray, outLen: Int) = ByteArray(outLen)
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = ByteArray(32)
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray) = ByteArray(0)
        override fun genericHash32(input: ByteArray) = ByteArray(32)
        override fun b64decode(s: String) = s.toByteArray()
        override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
        override val contentChunkSize = 1
        override fun u32le(n: Int) = ByteArray(4)
        override fun readU32le(b: ByteArray, o: Int) = 0
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = object : Crypto.ContentEncryptor {
            override val header = ByteArray(24)
            override fun encryptChunk(chunk: ByteArray, isLast: Boolean) = chunk
            override fun sealKey() = """{"c":"x","n":"y"}"""
        }
        override fun contentDecryptorFromKey(fileKey: ByteArray): de.ledgerline.app.core.crypto.Crypto.ContentDecryptor = throw NotImplementedError()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = throw NotImplementedError()
    }

    @Test fun save_preserves_payment_and_transaction_collections() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[],"caps":{},"payRef":"pay1","payKey":"pk","payHash":"ph","txRef":"tx1","txKey":"tk","txHash":"th","partRef":"part1","partKey":"prk","partHash":"prh","projRef":"proj1","projKey":"pjk","projHash":"pjh","partners":[{"id":"p1","name":"ACME"}]}"""

        var putBody: StorePutRequest? = null
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("shard1"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> {
                putBody = body
                return Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
        }
        val repo = FinanceRepository(sh, vh, crypto, FinanceCache(), tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })

        assertTrue(repo.load() is Outcome.Ok)
        val res = repo.save { list ->
            listOf(Invoice(id = "inv1", issueDate = "2026-06-15", lines = listOf(InvoiceLine(desc = "X", qty = 1.0, unitPrice = 100.0, vatRate = 19.0)))) + list
        }
        assertTrue(res is Outcome.Ok)
        assertEquals(1, (res as Outcome.Ok).value.manifest.invoices.size)

        // The re-sealed root still carries the OTHER collections + inline data.
        val root = Json.parseToJsonElement(putBody!!.ciphertext.removePrefix("SEALED:")).jsonObject
        assertEquals("pay1", root["payRef"]!!.jsonPrimitive.content)
        assertEquals("tx1", root["txRef"]!!.jsonPrimitive.content)
        assertEquals("part1", root["partRef"]!!.jsonPrimitive.content)   // partners collection preserved
        assertEquals("proj1", root["projRef"]!!.jsonPrimitive.content)   // projects collection preserved
        assertEquals("ACME", root["partners"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        // The invoices were re-sharded (a new shard blob) and the root points at it.
        assertTrue(root["shards"]!!.jsonArray.isNotEmpty())

        // The shards[] referential guard covers the new invoice shard + the preserved collection blobs.
        assertTrue(putBody!!.shards!!.containsAll(listOf("shard1", "pay1", "tx1", "part1", "proj1")))
    }

    @Test fun save_payment_methods_preserves_invoices_and_other_collections() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        // Root with an invoice shard + all three collections + inline partners.
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[{"ref":"invshard1","key":"ik","hash":"ih","count":1,"bucket":0}],"caps":{},"payRef":"pay1","payKey":"pk","payHash":"ph","txRef":"tx1","txKey":"tk","txHash":"th","catRef":"cat1","catKey":"ck","catHash":"ch","partRef":"part1","partKey":"prk","partHash":"prh","projRef":"proj1","projKey":"pjk","projHash":"pjh","partners":[{"id":"p1","name":"ACME"}],"invoiceSeq":7}"""

        var putBody: StorePutRequest? = null
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            // Every blob 404s → the invoice shard + collections degrade to empty, but their descriptors survive.
            override suspend fun rawInvoice(blob: String): Response<okhttp3.ResponseBody> =
                Response.error(404, "".toResponseBody(null))
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("newpay"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> {
                putBody = body
                return Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
        }
        val repo = FinanceRepository(sh, vh, crypto, FinanceCache(), tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })

        assertTrue(repo.load() is Outcome.Ok)
        val res = repo.savePaymentMethods { list ->
            listOf(de.ledgerline.app.domain.model.PaymentMethod(id = "pm1", type = "bank", label = "Giro", iban = "DE00")) + list
        }
        assertTrue(res is Outcome.Ok)

        val root = Json.parseToJsonElement(putBody!!.ciphertext.removePrefix("SEALED:")).jsonObject
        // The paymentMethods collection was re-sealed to the new blob…
        assertEquals("newpay", root["payRef"]!!.jsonPrimitive.content)
        // …while transactions, financeCategories, the invoice shard and inline data are untouched.
        assertEquals("tx1", root["txRef"]!!.jsonPrimitive.content)
        assertEquals("cat1", root["catRef"]!!.jsonPrimitive.content)
        assertEquals("invshard1", root["shards"]!!.jsonArray[0].jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("ACME", root["partners"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(7, root["invoiceSeq"]!!.jsonPrimitive.content.toInt())
        // The guard covers the invoice shard + the new payRef + the preserved tx/cat collections.
        assertTrue(putBody!!.shards!!.containsAll(listOf("invshard1", "newpay", "tx1", "cat1", "part1", "proj1")))
    }

    @Test fun save_transactions_preserves_invoices_and_other_collections() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[{"ref":"invshard1","key":"ik","hash":"ih","count":1,"bucket":0}],"caps":{},"payRef":"pay1","payKey":"pk","payHash":"ph","txRef":"tx1","txKey":"tk","txHash":"th","catRef":"cat1","catKey":"ck","catHash":"ch","partRef":"part1","partKey":"prk","partHash":"prh","projRef":"proj1","projKey":"pjk","projHash":"pjh","partners":[{"id":"p1","name":"ACME"}]}"""

        var putBody: StorePutRequest? = null
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            override suspend fun rawInvoice(blob: String): Response<okhttp3.ResponseBody> = Response.error(404, "".toResponseBody(null))
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("newtx"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> { putBody = body; return Response.success(StoreResponse(body.ciphertext, body.version + 1)) }
        }
        val repo = FinanceRepository(sh, vh, crypto, FinanceCache(), tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })

        assertTrue(repo.load() is Outcome.Ok)
        val res = repo.saveTransactions { list ->
            listOf(de.ledgerline.app.domain.model.Transaction(id = "t1", account = "a", date = "2026-06-01", amount = -12.5)) + list
        }
        assertTrue(res is Outcome.Ok)

        val root = Json.parseToJsonElement(putBody!!.ciphertext.removePrefix("SEALED:")).jsonObject
        assertEquals("newtx", root["txRef"]!!.jsonPrimitive.content)          // transactions re-sealed
        assertEquals("pay1", root["payRef"]!!.jsonPrimitive.content)          // methods untouched
        assertEquals("cat1", root["catRef"]!!.jsonPrimitive.content)
        assertEquals("invshard1", root["shards"]!!.jsonArray[0].jsonObject["ref"]!!.jsonPrimitive.content)
        assertTrue(putBody!!.shards!!.containsAll(listOf("invshard1", "newtx", "pay1", "cat1", "part1", "proj1")))
    }

    @Test fun save_partners_reseals_partRef_and_preserves_the_rest() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[{"ref":"invshard1","key":"ik","hash":"ih","count":1,"bucket":0}],"caps":{},"payRef":"pay1","payKey":"pk","payHash":"ph","txRef":"tx1","txKey":"tk","txHash":"th","catRef":"cat1","catKey":"ck","catHash":"ch","partRef":"part1","partKey":"prk","partHash":"prh","projRef":"proj1","projKey":"pjk","projHash":"pjh"}"""
        var putBody: StorePutRequest? = null
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            override suspend fun rawInvoice(blob: String): Response<okhttp3.ResponseBody> = Response.error(404, "".toResponseBody(null))
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("newpart"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> { putBody = body; return Response.success(StoreResponse(body.ciphertext, body.version + 1)) }
        }
        val repo = FinanceRepository(sh, vh, crypto, FinanceCache(), tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })

        assertTrue(repo.load() is Outcome.Ok)
        assertTrue(repo.savePartners { listOf(de.ledgerline.app.domain.model.Partner(id = "pt1", name = "ACME", category = "Software")) + it } is Outcome.Ok)

        val root = Json.parseToJsonElement(putBody!!.ciphertext.removePrefix("SEALED:")).jsonObject
        assertEquals("newpart", root["partRef"]!!.jsonPrimitive.content)
        assertEquals("proj1", root["projRef"]!!.jsonPrimitive.content)
        assertEquals("tx1", root["txRef"]!!.jsonPrimitive.content)
        assertEquals("cat1", root["catRef"]!!.jsonPrimitive.content)
        assertTrue(putBody!!.shards!!.containsAll(listOf("invshard1", "newpart", "pay1", "tx1", "cat1", "proj1")))
    }

    @Test fun receipt_document_blobs_stay_in_the_guard() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[],"caps":{},"txRef":"tx1","txKey":"tk","txHash":"th"}"""
        var putBody: StorePutRequest? = null
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            override suspend fun rawInvoice(blob: String): Response<okhttp3.ResponseBody> = Response.error(404, "".toResponseBody(null))
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("newtx"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> { putBody = body; return Response.success(StoreResponse(body.ciphertext, body.version + 1)) }
        }
        val repo = FinanceRepository(sh, vh, crypto, FinanceCache(), tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })
        assertTrue(repo.load() is Outcome.Ok)

        // A booking carrying a receipt whose document is content blob "rcptblob".
        val rawWithReceipt = Json.parseToJsonElement("""{"id":"t1","receipts":[{"id":"r1","blob":"rcptblob","key":"k"}]}""").jsonObject
        val tx = de.ledgerline.app.domain.model.Transaction(id = "t1", account = "a", date = "2026-06-01", amount = -9.0, raw = rawWithReceipt)
        assertTrue(repo.saveTransactions { listOf(tx) } is Outcome.Ok)
        // The receipt document blob is declared live so the server never frees it.
        assertTrue(putBody!!.shards!!.contains("rcptblob"))
    }

    @Test fun company_profile_is_cached_for_offline() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        var online = true
        val api = object : NotImplementedApi() {
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> {
                if (!online) throw java.io.IOException("offline")
                return Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto(name = "IntellyTec GmbH", vatId = "DE123")))
            }
        }
        val store = tmpStoreCache()   // the sealed disk cache shared across "app restarts"

        // Online: profile loads and is sealed into the offline cache.
        val repo1 = FinanceRepository(sh, vh, crypto, FinanceCache(), store, FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })
        assertEquals("IntellyTec GmbH", repo1.loadCompany()?.name)

        // Restart offline (fresh in-memory cache, same disk): the network throws but the sealed
        // disk cache still yields the profile.
        online = false
        val repo2 = FinanceRepository(sh, vh, crypto, FinanceCache(), store, FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), tmpOutbox(), apiProvider = { api })
        val cached = repo2.loadCompany()
        assertEquals("IntellyTec GmbH", cached?.name)
        assertEquals("DE123", cached?.vatId)
    }

    private fun invoice(id: String) =
        Invoice(id = id, issueDate = "2026-06-15", lines = listOf(InvoiceLine(desc = "X", qty = 1.0, unitPrice = 100.0, vatRate = 19.0)))

    /**
     * Regression (data loss): a created invoice whose root PUT fails with a recoverable server error
     * (5xx / 429 / exhausted-409 → ErrorKind.HTTP) must NOT be dropped — it stays in the optimistic
     * cache AND is queued to the durable outbox so [replayPending] can push it onto a later head.
     */
    @Test fun invoice_save_with_recoverable_error_is_queued_not_dropped() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[],"caps":{}}"""
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("shard1"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> = Response.error(500, "".toResponseBody(null))
        }
        val outbox = tmpOutbox()
        val cache = FinanceCache()
        val repo = FinanceRepository(sh, vh, crypto, cache, tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), FakeConnectivity(online = true), outbox, apiProvider = { api })
        assertTrue(repo.load() is Outcome.Ok)

        val res = repo.save { list -> listOf(invoice("inv1")) + list }

        assertTrue(res is Outcome.Ok)                                  // optimistic, not an error
        assertEquals(1, (res as Outcome.Ok).value.manifest.invoices.size)
        assertTrue(outbox.hasPending())                               // durably queued, not dropped
        assertEquals(1, cache.value.value!!.manifest.invoices.size)   // stays visible in the UI
    }

    /** An invoice created while OFFLINE queues immediately (no doomed PUT) + keeps the optimistic cache. */
    @Test fun invoice_save_offline_is_queued() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[],"caps":{}}"""
        var puts = 0
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = Response.success(de.ledgerline.app.data.remote.dto.CompanyResponse(CompanyDto()))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> { puts++; return Response.success(StoreResponse(body.ciphertext, 6)) }
        }
        val outbox = tmpOutbox()
        val cache = FinanceCache()
        val conn = FakeConnectivity(online = true)
        val repo = FinanceRepository(sh, vh, crypto, cache, tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), conn, outbox, apiProvider = { api })
        assertTrue(repo.load() is Outcome.Ok)
        conn.online = false

        val res = repo.save { list -> listOf(invoice("inv1")) + list }

        assertTrue(res is Outcome.Ok)
        assertEquals(0, puts)                    // never attempted a doomed PUT
        assertTrue(outbox.hasPending())          // queued
        assertEquals(1, cache.value.value!!.manifest.invoices.size)
    }
}
