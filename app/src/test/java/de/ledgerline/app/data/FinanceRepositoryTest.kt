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
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = throw NotImplementedError()
    }

    @Test fun save_preserves_payment_and_transaction_collections() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val rootJson = """{"v":3,"suite":1,"shardBits":0,"shards":[],"caps":{},"payRef":"pay1","payKey":"pk","payHash":"ph","txRef":"tx1","txKey":"tk","txHash":"th","partners":[{"id":"p1","name":"ACME"}]}"""

        var putBody: StorePutRequest? = null
        val api = object : NotImplementedApi() {
            override suspend fun invoicesStore(): Response<StoreResponse> = Response.success(StoreResponse("SEALED:$rootJson", 5))
            override suspend fun company(): Response<CompanyDto> = Response.success(CompanyDto())
            override suspend fun uploadInvoice(file: MultipartBody.Part): Response<UploadResponse> = Response.success(UploadResponse("shard1"))
            override suspend fun invoicesStorePut(body: StorePutRequest): Response<StoreResponse> {
                putBody = body
                return Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
        }
        val repo = FinanceRepository(sh, vh, crypto, FinanceCache(), tmpStoreCache(), FakeOfflineFlags(), tmpBlobCache(), apiProvider = { api })

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
        assertEquals("ACME", root["partners"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        // The invoices were re-sharded (a new shard blob) and the root points at it.
        assertTrue(root["shards"]!!.jsonArray.isNotEmpty())

        // The shards[] referential guard covers the new invoice shard + the preserved collection blobs.
        assertTrue(putBody!!.shards!!.containsAll(listOf("shard1", "pay1", "tx1")))
    }
}
