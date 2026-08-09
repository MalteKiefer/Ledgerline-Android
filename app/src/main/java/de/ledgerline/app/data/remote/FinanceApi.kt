package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.finance.AccountVatSummary
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Single-record wrappers the finance endpoints return (`{ invoice: … }`, etc.). */
@Serializable data class InvoiceResponse(val invoice: Invoice)
@Serializable data class TransactionResponse(val transaction: BankTransaction)
@Serializable data class PartnerResponse(val partner: FinancePartner)
@Serializable data class PaymentMethodResponse(@SerialName("payment_method") val paymentMethod: PaymentMethod)
@Serializable data class ProjectResponse(val project: FinanceProject)
@Serializable data class CategoryResponse(val category: FinanceCategory)
@Serializable data class CompanyResponse(val company: CompanyProfile)
@Serializable data class ReceiptResponse(val receipt: de.ledgerline.app.domain.model.finance.FinanceReceipt)
@Serializable data class OkBody(val ok: Boolean = false)
@Serializable data class BulkResult(val created: Int = 0, val skipped: Int = 0)
@Serializable data class CategorySuggestionsResponse(val suggestions: List<de.ledgerline.app.domain.model.finance.CategorySuggestion> = emptyList())
@Serializable data class OcrResult(val text: String = "", val source: String = "", val pages: Int = 0)

/**
 * The plaintext-relational finance + company REST surface (server pivot v1.5xx). Every mutation is a
 * per-record call with an optimistic-concurrency `version` in the body (PUT → 409 `{error, version}`
 * on conflict); deletes are soft (trash) with `/restore` + `/force`. Bodies are free-form
 * [JsonObject]s (the API accepts `additionalProperties: true`) so the repository controls exactly
 * which fields + the `version` it sends. No client crypto — payloads are plaintext over TLS.
 */
interface FinanceApi {
    @GET("api/v1/finance/data")
    suspend fun financeData(): Response<FinanceData>

    @GET("api/v1/finance/reports")
    suspend fun financeReports(@Query("year") year: Int?): Response<FinanceReports>

    @GET("api/v1/finance/reports/account-vat")
    suspend fun accountVat(@Query("account_id") accountId: Int, @Query("year") year: Int?): Response<AccountVatSummary>

    @GET("api/v1/finance/reports/vat-advance")
    suspend fun vatAdvance(@Query("year") year: Int?, @Query("quarter") quarter: Int?): Response<de.ledgerline.app.domain.model.finance.VatAdvanceReturn>

    @GET("api/v1/finance/reports/euer")
    suspend fun euer(@Query("year") year: Int?): Response<de.ledgerline.app.domain.model.finance.EuerReport>

    @GET("api/v1/finance/duplicates")
    suspend fun financeDuplicates(): Response<FinanceDuplicates>

    @GET("api/v1/finance/category-suggestions")
    suspend fun categorySuggestions(): Response<CategorySuggestionsResponse>

    @GET("api/v1/finance/trash")
    suspend fun financeTrash(): Response<de.ledgerline.app.domain.model.finance.FinanceTrash>

    // ---- Invoices ----
    @POST("api/v1/finance/invoices")
    suspend fun createInvoice(@Body body: JsonObject): Response<InvoiceResponse>

    @PUT("api/v1/finance/invoices/{id}")
    suspend fun updateInvoice(@Path("id") id: Int, @Body body: JsonObject): Response<InvoiceResponse>

    @DELETE("api/v1/finance/invoices/{id}")
    suspend fun deleteInvoice(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/invoices/{id}/restore")
    suspend fun restoreInvoice(@Path("id") id: Int): Response<InvoiceResponse>

    @DELETE("api/v1/finance/invoices/{id}/force")
    suspend fun forceInvoice(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/invoices/{id}/finalize")
    suspend fun finalizeInvoice(@Path("id") id: Int): Response<InvoiceResponse>

    @GET("api/v1/finance/invoices/{id}/pdf")
    @Streaming
    suspend fun invoicePdf(@Path("id") id: Int): Response<ResponseBody>

    @Multipart
    @POST("api/v1/finance/invoices/{id}/pdf")
    suspend fun uploadInvoicePdf(@Path("id") id: Int, @Part file: MultipartBody.Part): Response<InvoiceResponse>

    @POST("api/v1/finance/invoices/{id}/email")
    suspend fun emailInvoice(@Path("id") id: Int, @Body body: JsonObject): Response<OkBody>

    @POST("api/v1/finance/invoices/{id}/storno")
    suspend fun stornoInvoice(@Path("id") id: Int): Response<InvoiceResponse>

    @POST("api/v1/finance/invoices/{id}/dun")
    suspend fun dunInvoice(@Path("id") id: Int, @Body body: JsonObject): Response<OkBody>

    // ---- Bank transactions ----
    @POST("api/v1/finance/transactions")
    suspend fun createTransaction(@Body body: JsonObject): Response<TransactionResponse>

    @POST("api/v1/finance/transactions/bulk")
    suspend fun bulkTransactions(@Body body: JsonObject): Response<BulkResult>

    @PUT("api/v1/finance/transactions/{id}")
    suspend fun updateTransaction(@Path("id") id: Int, @Body body: JsonObject): Response<TransactionResponse>

    @DELETE("api/v1/finance/transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/transactions/{id}/restore")
    suspend fun restoreTransaction(@Path("id") id: Int): Response<TransactionResponse>

    @DELETE("api/v1/finance/transactions/{id}/force")
    suspend fun forceTransaction(@Path("id") id: Int): Response<OkBody>

    @Multipart
    @POST("api/v1/finance/transactions/{id}/receipts")
    suspend fun attachReceipt(@Path("id") id: Int, @Part file: MultipartBody.Part): Response<TransactionResponse>

    @DELETE("api/v1/finance/transactions/{id}/receipts/{receipt}")
    suspend fun deleteReceipt(@Path("id") id: Int, @Path("receipt") receipt: String): Response<TransactionResponse>

    @GET("api/v1/finance/transactions/{id}/receipts/{receipt}/raw")
    @Streaming
    suspend fun receiptRaw(@Path("id") id: Int, @Path("receipt") receipt: String): Response<ResponseBody>

    // ---- Partners ----
    @POST("api/v1/finance/partners")
    suspend fun createPartner(@Body body: JsonObject): Response<PartnerResponse>

    @PUT("api/v1/finance/partners/{id}")
    suspend fun updatePartner(@Path("id") id: Int, @Body body: JsonObject): Response<PartnerResponse>

    @DELETE("api/v1/finance/partners/{id}")
    suspend fun deletePartner(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/partners/{id}/restore")
    suspend fun restorePartner(@Path("id") id: Int): Response<PartnerResponse>

    @DELETE("api/v1/finance/partners/{id}/force")
    suspend fun forcePartner(@Path("id") id: Int): Response<OkBody>

    // ---- Payment methods ----
    @POST("api/v1/finance/payment-methods")
    suspend fun createPaymentMethod(@Body body: JsonObject): Response<PaymentMethodResponse>

    @PUT("api/v1/finance/payment-methods/{id}")
    suspend fun updatePaymentMethod(@Path("id") id: Int, @Body body: JsonObject): Response<PaymentMethodResponse>

    @DELETE("api/v1/finance/payment-methods/{id}")
    suspend fun deletePaymentMethod(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/payment-methods/{id}/restore")
    suspend fun restorePaymentMethod(@Path("id") id: Int): Response<PaymentMethodResponse>

    @DELETE("api/v1/finance/payment-methods/{id}/force")
    suspend fun forcePaymentMethod(@Path("id") id: Int): Response<OkBody>

    // ---- Projects ----
    @POST("api/v1/finance/projects")
    suspend fun createProject(@Body body: JsonObject): Response<ProjectResponse>

    @PUT("api/v1/finance/projects/{id}")
    suspend fun updateProject(@Path("id") id: Int, @Body body: JsonObject): Response<ProjectResponse>

    @DELETE("api/v1/finance/projects/{id}")
    suspend fun deleteProject(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/projects/{id}/restore")
    suspend fun restoreProject(@Path("id") id: Int): Response<ProjectResponse>

    @DELETE("api/v1/finance/projects/{id}/force")
    suspend fun forceProject(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/projects/{id}/move")
    suspend fun moveProject(@Path("id") id: Int, @Body body: JsonObject): Response<ProjectResponse>

    // ---- Categories ----
    @POST("api/v1/finance/categories")
    suspend fun createCategory(@Body body: JsonObject): Response<CategoryResponse>

    @PUT("api/v1/finance/categories/{id}")
    suspend fun updateCategory(@Path("id") id: Int, @Body body: JsonObject): Response<CategoryResponse>

    @DELETE("api/v1/finance/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int): Response<OkBody>

    // ---- Standalone receipts (Fremdbelege) ----
    @Multipart
    @POST("api/v1/finance/receipts")
    suspend fun storeReceipt(@Part parts: List<MultipartBody.Part>): Response<ReceiptResponse>

    @PUT("api/v1/finance/receipts/{id}")
    suspend fun updateReceipt(@Path("id") id: Int, @Body body: JsonObject): Response<ReceiptResponse>

    @DELETE("api/v1/finance/receipts/{id}")
    suspend fun deleteStandaloneReceipt(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/finance/receipts/{id}/restore")
    suspend fun restoreStandaloneReceipt(@Path("id") id: Int): Response<ReceiptResponse>

    @DELETE("api/v1/finance/receipts/{id}/force")
    suspend fun forceStandaloneReceipt(@Path("id") id: Int): Response<OkBody>

    @GET("api/v1/finance/receipts/{id}/raw")
    @Streaming
    suspend fun standaloneReceiptRaw(@Path("id") id: Int): Response<ResponseBody>

    // ---- OCR (transient plaintext) ----
    @Multipart
    @POST("api/v1/invoices/ocr")
    suspend fun ocr(@Part file: MultipartBody.Part): Response<OcrResult>

    // ---- Company profile ----
    @GET("api/v1/company")
    suspend fun company(): Response<CompanyResponse>

    @PUT("api/v1/company")
    suspend fun updateCompany(@Body body: CompanyProfile): Response<CompanyResponse>

    @Multipart
    @PUT("api/v1/company")
    suspend fun updateCompanyMultipart(@Part parts: List<MultipartBody.Part>): Response<CompanyResponse>

    @GET("api/v1/company/logo")
    @Streaming
    suspend fun companyLogo(): Response<ResponseBody>
}
