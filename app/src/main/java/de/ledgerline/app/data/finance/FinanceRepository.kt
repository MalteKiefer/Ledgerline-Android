package de.ledgerline.app.data.finance

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.data.remote.FinanceApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.finance.BankTransaction
import de.ledgerline.app.domain.model.finance.CompanyProfile
import de.ledgerline.app.domain.model.finance.FinanceCategory
import de.ledgerline.app.domain.model.finance.FinanceData
import de.ledgerline.app.domain.model.finance.FinanceDuplicates
import de.ledgerline.app.domain.model.finance.FinancePartner
import de.ledgerline.app.domain.model.finance.FinanceProject
import de.ledgerline.app.domain.model.finance.FinanceReports
import de.ledgerline.app.domain.model.finance.Invoice
import de.ledgerline.app.domain.model.finance.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The finance data layer for the plaintext-relational pivot (no client crypto). [data] is the
 * in-memory snapshot the UI observes; [load] is **cache-first** — paints the last snapshot from a
 * plaintext disk cache immediately, then refreshes from `GET /finance/data`. Mutations are per-record
 * REST with an optimistic `version` in the body (PUT → 409 on conflict); on success the returned
 * record is patched into the snapshot (+ disk cache) for a snappy UI, and a later [load] reconciles
 * server-computed fields (numbers, seq, receipts).
 *
 * Reads work offline from the disk cache. The offline write-queue is phase 2b — a mutation offline
 * currently returns a NETWORK error (no data loss; the edit just isn't applied).
 */
@Singleton
class FinanceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val sessionHolder: SessionHolder,
    private val connectivity: Connectivity,
    private val outbox: FinanceOutbox,
) {
    private val cacheFile = File(context.filesDir, "finance_data.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val _data = MutableStateFlow<FinanceData?>(null)
    val data: StateFlow<FinanceData?> = _data.asStateFlow()

    private fun api(): FinanceApi {
        val s = sessionHolder.get() ?: error("no session")
        return NetworkFactory.createFinance(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin)
    }

    private fun readDisk(): FinanceData? =
        runCatching { if (cacheFile.exists()) json.decodeFromString(FinanceData.serializer(), cacheFile.readText()) else null }.getOrNull()

    private fun writeDisk(d: FinanceData) {
        runCatching {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(FinanceData.serializer(), d))
            tmp.renameTo(cacheFile)
        }
    }

    fun clear() {
        _data.value = null
        runCatching { cacheFile.delete() }
    }

    /** Cache-first load: emit the disk snapshot immediately, then refresh from the server. */
    suspend fun load(): Outcome<FinanceData> = withContext(Dispatchers.IO) {
        sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        if (_data.value == null) readDisk()?.let { _data.value = it }
        // Push any queued offline edits before pulling fresh, so the snapshot reflects them.
        runCatching { replayPending() }
        try {
            val res = api().financeData()
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> _data.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK)
                else -> {
                    val body = res.body()!!
                    publish(body)
                    Outcome.Ok(body)
                }
            }
        } catch (e: Exception) {
            _data.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
        }
    }

    private fun publish(d: FinanceData) { _data.value = d; writeDisk(d) }
    private fun cur() = _data.value ?: FinanceData()

    private fun upsertInvoice(v: Invoice) = publish(cur().let { it.copy(invoices = it.invoices.upsert(v) { x -> x.id == v.id }) })
    private fun upsertTransaction(v: BankTransaction) = publish(cur().let { it.copy(transactions = it.transactions.upsert(v) { x -> x.id == v.id }) })
    private fun upsertPartner(v: FinancePartner) = publish(cur().let { it.copy(partners = it.partners.upsert(v) { x -> x.id == v.id }) })
    private fun upsertPayment(v: PaymentMethod) = publish(cur().let { it.copy(paymentMethods = it.paymentMethods.upsert(v) { x -> x.id == v.id }) })
    private fun upsertProject(v: FinanceProject) = publish(cur().let { it.copy(projects = it.projects.upsert(v) { x -> x.id == v.id }) })
    private fun upsertCategory(v: FinanceCategory) = publish(cur().let { it.copy(financeCategories = it.financeCategories.upsert(v) { x -> x.id == v.id }) })

    // ---- Invoices ---- (create/finalize/restore online-only; update/delete offline-queued)
    suspend fun createInvoice(body: JsonObject) = record({ api().createInvoice(body) }, { it.invoice }, ::upsertInvoice)
    suspend fun updateInvoice(id: Int, body: JsonObject) = update("invoice", id, body, Invoice.serializer(), cur().invoices.firstOrNull { it.id == id }, ::upsertInvoice, { api().updateInvoice(id, body) }, { it.invoice })
    suspend fun finalizeInvoice(id: Int) = record({ api().finalizeInvoice(id) }, { it.invoice }, ::upsertInvoice)
    suspend fun restoreInvoice(id: Int) = record({ api().restoreInvoice(id) }, { it.invoice }, ::upsertInvoice)
    suspend fun deleteInvoice(id: Int) = deleteQueued("invoice", id, { api().deleteInvoice(id) }) { publish(cur().let { d -> d.copy(invoices = d.invoices.filterNot { it.id == id }) }) }

    // ---- Transactions ----
    suspend fun createTransaction(body: JsonObject) = record({ api().createTransaction(body) }, { it.transaction }, ::upsertTransaction)
    suspend fun updateTransaction(id: Int, body: JsonObject) = update("transaction", id, body, BankTransaction.serializer(), cur().transactions.firstOrNull { it.id == id }, ::upsertTransaction, { api().updateTransaction(id, body) }, { it.transaction })
    suspend fun deleteTransaction(id: Int) = deleteQueued("transaction", id, { api().deleteTransaction(id) }) { publish(cur().let { d -> d.copy(transactions = d.transactions.filterNot { it.id == id }) }) }

    // ---- Partners ----
    suspend fun createPartner(body: JsonObject) = record({ api().createPartner(body) }, { it.partner }, ::upsertPartner)
    suspend fun updatePartner(id: Int, body: JsonObject) = update("partner", id, body, FinancePartner.serializer(), cur().partners.firstOrNull { it.id == id }, ::upsertPartner, { api().updatePartner(id, body) }, { it.partner })
    suspend fun deletePartner(id: Int) = deleteQueued("partner", id, { api().deletePartner(id) }) { publish(cur().let { d -> d.copy(partners = d.partners.filterNot { it.id == id }) }) }

    // ---- Payment methods ----
    suspend fun createPaymentMethod(body: JsonObject) = record({ api().createPaymentMethod(body) }, { it.paymentMethod }, ::upsertPayment)
    suspend fun updatePaymentMethod(id: Int, body: JsonObject) = update("paymentMethod", id, body, PaymentMethod.serializer(), cur().paymentMethods.firstOrNull { it.id == id }, ::upsertPayment, { api().updatePaymentMethod(id, body) }, { it.paymentMethod })
    suspend fun deletePaymentMethod(id: Int) = deleteQueued("paymentMethod", id, { api().deletePaymentMethod(id) }) { publish(cur().let { d -> d.copy(paymentMethods = d.paymentMethods.filterNot { it.id == id }) }) }

    // ---- Projects ----
    suspend fun createProject(body: JsonObject) = record({ api().createProject(body) }, { it.project }, ::upsertProject)
    suspend fun updateProject(id: Int, body: JsonObject) = update("project", id, body, FinanceProject.serializer(), cur().projects.firstOrNull { it.id == id }, ::upsertProject, { api().updateProject(id, body) }, { it.project })
    suspend fun moveProject(id: Int, body: JsonObject) = record({ api().moveProject(id, body) }, { it.project }, ::upsertProject)
    suspend fun deleteProject(id: Int) = deleteQueued("project", id, { api().deleteProject(id) }) { publish(cur().let { d -> d.copy(projects = d.projects.filterNot { it.id == id }) }) }

    // ---- Categories (online-only; trivial) ----
    suspend fun createCategory(body: JsonObject) = record({ api().createCategory(body) }, { it.category }, ::upsertCategory)
    suspend fun updateCategory(id: Int, body: JsonObject) = record({ api().updateCategory(id, body) }, { it.category }, ::upsertCategory)
    suspend fun deleteCategory(id: Int) = delete({ api().deleteCategory(id) }) { publish(cur().let { d -> d.copy(financeCategories = d.financeCategories.filterNot { it.id == id }) }) }

    // ---- Read-only server analytics (always live) ----
    suspend fun reports(year: Int?): FinanceReports? = get { api().financeReports(year) }
    suspend fun duplicates(): FinanceDuplicates? = get { api().financeDuplicates() }
    suspend fun categorySuggestions() = get { api().categorySuggestions() }?.suggestions.orEmpty()
    suspend fun accountVat(accountId: Int, year: Int?) = get { api().accountVat(accountId, year) }

    /** Download an invoice's server-rendered / imported-original PDF bytes, or null. */
    suspend fun invoicePdf(id: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().invoicePdf(id).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }

    // ---- Receipts (online-only; multipart file bytes) ----
    suspend fun attachReceipt(txId: Int, bytes: ByteArray, fileName: String, mime: String): Outcome<BankTransaction> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return withContext(Dispatchers.IO) {
            try {
                val part = okhttp3.MultipartBody.Part.createFormData(
                    "file", fileName,
                    bytes.toRequestBody(mime.toMediaTypeOrNull()),
                )
                val res = api().attachReceipt(txId, part)
                val t = res.takeIf { it.isSuccessful }?.body()?.transaction ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
                upsertTransaction(t); Outcome.Ok(t)
            } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
        }
    }

    suspend fun deleteReceipt(txId: Int, receiptId: String): Outcome<BankTransaction> = withContext(Dispatchers.IO) {
        if (!connectivity.isOnline()) return@withContext Outcome.Err(ErrorKind.NETWORK)
        try {
            val res = api().deleteReceipt(txId, receiptId)
            val t = res.takeIf { it.isSuccessful }?.body()?.transaction ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            upsertTransaction(t); Outcome.Ok(t)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    suspend fun receiptBytes(txId: Int, receiptId: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().receiptRaw(txId, receiptId).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }

    /** Bulk-import parsed statement lines for one account (server dedups by signature). Returns
     *  (created, skipped), then reloads so the new rows appear. Null on failure/offline. */
    suspend fun bulkImport(paymentMethodId: Int, lines: List<JsonObject>): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (!connectivity.isOnline()) return@withContext null
        val body = kotlinx.serialization.json.buildJsonObject {
            put("payment_method_id", kotlinx.serialization.json.JsonPrimitive(paymentMethodId))
            put("transactions", kotlinx.serialization.json.JsonArray(lines))
        }
        try {
            val res = api().bulkTransactions(body)
            val r = res.takeIf { it.isSuccessful }?.body() ?: return@withContext null
            load()
            r.created to r.skipped
        } catch (_: Exception) { null }
    }

    // ---- Company profile ----
    suspend fun company(): CompanyProfile? = get { api().company() }?.company
    suspend fun updateCompany(profile: CompanyProfile): CompanyProfile? = get { api().updateCompany(profile) }?.company

    // ---- generic helpers ----
    /** A record create/update: extract the record from the `{record}` wrapper + patch the cache. */
    private suspend fun <W, R> record(
        call: suspend () -> Response<W>,
        extract: (W) -> R?,
        upsert: (R) -> Unit,
    ): Outcome<R> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return withContext(Dispatchers.IO) {
            try {
                val res = call()
                if (!res.isSuccessful) {
                    return@withContext Outcome.Err(if (res.code() == 409) ErrorKind.HTTP else ErrorKind.NETWORK)
                }
                val r = res.body()?.let(extract) ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
                upsert(r)
                Outcome.Ok(r)
            } catch (e: Exception) {
                Outcome.Err(ErrorKind.NETWORK, e)
            }
        }
    }

    private suspend fun delete(call: suspend () -> Response<*>, onOk: () -> Unit): Outcome<Unit> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return withContext(Dispatchers.IO) {
            try {
                if (call().isSuccessful) { onOk(); Outcome.Ok(Unit) } else Outcome.Err(ErrorKind.NETWORK)
            } catch (e: Exception) {
                Outcome.Err(ErrorKind.NETWORK, e)
            }
        }
    }

    private suspend fun <T> get(call: suspend () -> Response<T>): T? = withContext(Dispatchers.IO) {
        runCatching { call().takeIf { it.isSuccessful }?.body() }.getOrNull()
    }

    // ---- Offline write queue (update/delete of existing records; create stays online) ----
    /** Overlay [body]'s fields onto [current] (re-serialize → merge → decode) for an optimistic patch. */
    private fun <R> mergeRecord(current: R, body: JsonObject, serializer: kotlinx.serialization.KSerializer<R>): R {
        val base = json.encodeToJsonElement(serializer, current).jsonObject
        return json.decodeFromJsonElement(serializer, JsonObject(base + body))
    }

    private suspend fun <W, R> update(
        entity: String, id: Int, body: JsonObject, serializer: kotlinx.serialization.KSerializer<R>,
        current: R?, upsert: (R) -> Unit,
        call: suspend () -> Response<W>, pick: (W) -> R?,
    ): Outcome<R> {
        if (!connectivity.isOnline()) {
            val c = current ?: return Outcome.Err(ErrorKind.NETWORK)
            val merged = mergeRecord(c, body, serializer); upsert(merged)
            outbox.addCoalesced(FinanceOp(entity, "update", id, body))
            return Outcome.Ok(merged)
        }
        return withContext(Dispatchers.IO) {
            try {
                val res = call()
                if (res.isSuccessful) {
                    val r = res.body()?.let(pick) ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
                    upsert(r); Outcome.Ok(r)
                } else {
                    Outcome.Err(if (res.code() == 409) ErrorKind.HTTP else ErrorKind.NETWORK) // server rejection ≠ offline
                }
            } catch (e: Exception) {
                val c = current ?: return@withContext Outcome.Err(ErrorKind.NETWORK, e)
                val merged = mergeRecord(c, body, serializer); upsert(merged)
                outbox.addCoalesced(FinanceOp(entity, "update", id, body))
                Outcome.Ok(merged)
            }
        }
    }

    private suspend fun deleteQueued(entity: String, id: Int, call: suspend () -> Response<*>, removeCache: () -> Unit): Outcome<Unit> {
        if (!connectivity.isOnline()) {
            removeCache(); outbox.addCoalesced(FinanceOp(entity, "delete", id)); return Outcome.Ok(Unit)
        }
        return withContext(Dispatchers.IO) {
            try {
                if (call().isSuccessful) { removeCache(); Outcome.Ok(Unit) } else Outcome.Err(ErrorKind.NETWORK)
            } catch (e: Exception) {
                removeCache(); outbox.addCoalesced(FinanceOp(entity, "delete", id)); Outcome.Ok(Unit)
            }
        }
    }

    /** Replay queued offline ops FIFO. Stops on the first network failure (keeps it for later); a
     *  server 409/404 during replay is treated as done (server state wins). Runs before each load. */
    suspend fun replayPending() = withContext(Dispatchers.IO) {
        if (!connectivity.isOnline() || sessionHolder.get() == null) return@withContext
        for (op in outbox.all()) {
            val done = try { runOp(op) } catch (_: Exception) { false }
            if (done) outbox.remove(op) else break
        }
    }

    private suspend fun runOp(op: FinanceOp): Boolean {
        val a = api()
        val res: Response<*> = when ("${op.entity}:${op.action}") {
            "invoice:update" -> a.updateInvoice(op.id, op.body!!)
            "invoice:delete" -> a.deleteInvoice(op.id)
            "transaction:update" -> a.updateTransaction(op.id, op.body!!)
            "transaction:delete" -> a.deleteTransaction(op.id)
            "partner:update" -> a.updatePartner(op.id, op.body!!)
            "partner:delete" -> a.deletePartner(op.id)
            "paymentMethod:update" -> a.updatePaymentMethod(op.id, op.body!!)
            "paymentMethod:delete" -> a.deletePaymentMethod(op.id)
            "project:update" -> a.updateProject(op.id, op.body!!)
            "project:delete" -> a.deleteProject(op.id)
            else -> return true // unknown op → drop
        }
        return res.isSuccessful || res.code() == 409 || res.code() == 404
    }
}

/** Replace the element matching [where] with [v], or append it when none matches. */
private inline fun <T> List<T>.upsert(v: T, where: (T) -> Boolean): List<T> {
    val i = indexOfFirst(where)
    return if (i >= 0) toMutableList().also { it[i] = v } else this + v
}
