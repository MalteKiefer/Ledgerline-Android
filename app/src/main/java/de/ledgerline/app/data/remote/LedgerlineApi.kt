package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.EmbedTextRequest
import de.ledgerline.app.data.remote.dto.EmbedTextResponse
import de.ledgerline.app.data.remote.dto.MeResponse
import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.data.remote.dto.PairClaimResponse
import de.ledgerline.app.data.remote.dto.PairPollResponse
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.data.remote.dto.ReconcileRequest
import de.ledgerline.app.data.remote.dto.ReconcileResponse
import de.ledgerline.app.data.remote.dto.ReverseResponse
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.data.remote.dto.UploadResponse
import de.ledgerline.app.data.remote.dto.UsageResponse
import de.ledgerline.app.data.remote.dto.VaultResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface LedgerlineApi {
    @POST("api/v1/auth/pair")
    suspend fun claimPair(@Body body: PairClaimRequest): Response<PairClaimResponse>

    // Poll for pairing approval. The server moved this from GET /auth/pair?code= to
    // POST /auth/pair/collect { code } (the GET now 405s).
    @POST("api/v1/auth/pair/collect")
    suspend fun pollPair(@Body body: de.ledgerline.app.data.remote.dto.PairCollectRequest): Response<PairPollResponse>

    // Connected-device management (owner-scoped).
    @GET("api/v1/devices")
    suspend fun devices(): Response<de.ledgerline.app.data.remote.dto.DevicesResponse>

    @DELETE("api/v1/devices/{token}")
    suspend fun revokeDevice(@Path("token") token: String): Response<Unit>

    @POST("api/v1/devices/{token}/wipe")
    suspend fun wipeDevice(@Path("token") token: String): Response<Unit>

    @GET("api/v1/me")
    suspend fun me(): Response<MeResponse>

    /** Update the global display preferences (units + clock). Partial patch accepted. */
    @POST("api/v1/preferences")
    suspend fun putPreferences(@Body body: de.ledgerline.app.data.remote.dto.DisplayPrefsDto): Response<Unit>

    // Password-manager enrichment (nothing stored server-side).
    @GET("api/v1/passwords/breach")
    @Streaming
    suspend fun passwordsBreach(@Query("prefix") prefix: String): Response<ResponseBody>

    @GET("api/v1/passwords/icon")
    suspend fun passwordsIcon(@Query("domain") domain: String): Response<de.ledgerline.app.data.remote.dto.IconResponse>

    @GET("api/v1/passwords/tfa-directory")
    suspend fun passwordsTfaDirectory(): Response<de.ledgerline.app.data.remote.dto.TfaDirectoryResponse>

    @GET("api/v1/vault")
    suspend fun vault(): Response<VaultResponse>

    // Cross-user sharing identity (X25519 + ML-KEM-768). Write-once: PUT returns
    // {ok:true} or 409 key_conflict if the public_key changes (CLAUDE.md §3).
    @GET("api/v1/vaults/keys")
    suspend fun vaultKeys(): Response<de.ledgerline.app.data.remote.dto.VaultKeysResponse>

    @PUT("api/v1/vaults/keys")
    suspend fun putVaultKeys(@Body body: de.ledgerline.app.data.remote.dto.PublishKeysRequest): Response<Unit>

    // Store v3: per-module sealed stores. The monolith `/store` was removed
    // server-side (bare `/store` 404s); each workspace module has its own store.
    // module ∈ { notes, todos, bookmarks, contacts, … } (see CLAUDE.md §3).
    @GET("api/v1/store/{module}")
    suspend fun moduleStore(@Path("module") module: String): Response<StoreResponse>

    @PUT("api/v1/store/{module}")
    suspend fun putModuleStore(
        @Path("module") module: String,
        @Body body: StorePutRequest,
    ): Response<StoreResponse>

    @Deprecated("Store v3: the monolith /store was removed server-side. Use moduleStore/putModuleStore.")
    @GET("api/v1/store")
    suspend fun store(): Response<StoreResponse>

    @DELETE("api/v1/auth/session")
    suspend fun deleteSession(): Response<Unit>

    @GET("api/v1/files/raw/{blob}")
    @Streaming
    suspend fun rawFile(@Path("blob") blob: String): Response<ResponseBody>

    @Multipart
    @POST("api/v1/files/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): Response<UploadResponse>

    // S3-multipart chunked upload for large files/videos (≥64 MiB). init → parts → complete.
    @POST("api/v1/files/upload/init")
    suspend fun filesUploadInit(@Body body: de.ledgerline.app.data.remote.dto.UploadInitRequest): Response<de.ledgerline.app.data.remote.dto.UploadInitResponse>

    @Multipart
    @POST("api/v1/files/upload/part")
    suspend fun filesUploadPart(
        @Part("token") token: okhttp3.RequestBody,
        @Part("part") part: okhttp3.RequestBody,
        @Part chunk: MultipartBody.Part,
    ): Response<de.ledgerline.app.data.remote.dto.UploadPartResponse>

    @POST("api/v1/files/upload/complete")
    suspend fun filesUploadComplete(@Body body: de.ledgerline.app.data.remote.dto.UploadCompleteRequest): Response<UploadResponse>

    @POST("api/v1/files/upload/abort")
    suspend fun filesUploadAbort(@Body body: de.ledgerline.app.data.remote.dto.UploadAbortRequest): Response<Unit>

    @DELETE("api/v1/files/blob/{blob}")
    suspend fun deleteBlob(@Path("blob") blob: String): Response<Unit>

    // Living-set reconcile: the server frees any files blob not in this list (24h grace).
    @POST("api/v1/files/blobs/reconcile")
    suspend fun filesReconcile(@Body body: ReconcileRequest): Response<ReconcileResponse>

    // Living-set reconcile for gallery blobs (reclaims orphaned photo/shard blobs, 24h grace).
    @POST("api/v1/gallery/blobs/reconcile")
    suspend fun galleryReconcile(@Body body: ReconcileRequest): Response<ReconcileResponse>

    // Store v3 sharded files index (root pointer table + external shard/collection blobs).
    // --- Notes sharded store + blobs (web migrated notes off the monolith, §P0) ---
    @GET("api/v1/notes/store")
    suspend fun notesStore(): Response<StoreResponse>

    @PUT("api/v1/notes/store")
    suspend fun notesStorePut(@Body body: StorePutRequest): Response<StoreResponse>

    @GET("api/v1/notes/raw/{blob}")
    @Streaming
    suspend fun rawNote(@Path("blob") blob: String): Response<ResponseBody>

    @Multipart
    @POST("api/v1/notes/upload")
    suspend fun uploadNote(@Part file: MultipartBody.Part): Response<UploadResponse>

    // --- Finance / invoices sharded store + blobs + non-secret company profile ---
    @GET("api/v1/invoices/store")
    suspend fun invoicesStore(): Response<StoreResponse>

    @PUT("api/v1/invoices/store")
    suspend fun invoicesStorePut(@Body body: StorePutRequest): Response<StoreResponse>

    @GET("api/v1/invoices/raw/{blob}")
    @Streaming
    suspend fun rawInvoice(@Path("blob") blob: String): Response<ResponseBody>

    @Multipart
    @POST("api/v1/invoices/upload")
    suspend fun uploadInvoice(@Part file: MultipartBody.Part): Response<UploadResponse>

    @POST("api/v1/invoices/raw-batch")
    @Streaming
    suspend fun invoicesRawBatch(@Body body: ReconcileRequest): Response<ResponseBody>

    @POST("api/v1/invoices/blobs/reconcile")
    suspend fun invoicesReconcile(@Body body: ReconcileRequest): Response<ReconcileResponse>

    /** Server-side receipt OCR (transient cleartext; returns line-structured text). Optional endpoint. */
    @Multipart
    @POST("api/v1/invoices/ocr")
    suspend fun invoicesOcr(@Part file: MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.OcrResponse>

    /** The signed-in user's stored non-secret avatar (streamed image; 404 when none). */
    @GET("api/v1/avatar")
    @Streaming
    suspend fun avatar(): Response<ResponseBody>

    @GET("api/v1/company")
    suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse>

    @GET("api/v1/company/logo")
    @Streaming
    suspend fun companyLogo(): Response<ResponseBody>

    @PUT("api/v1/company")
    suspend fun companyPut(@Body body: de.ledgerline.app.data.remote.dto.CompanyDto): Response<de.ledgerline.app.data.remote.dto.CompanyResponse>

    // --- Passwords sharded store + blobs (web migrated passwords off the monolith, §P0) ---
    @GET("api/v1/passwords/store")
    suspend fun passwordsStore(): Response<StoreResponse>

    @PUT("api/v1/passwords/store")
    suspend fun passwordsStorePut(@Body body: StorePutRequest): Response<StoreResponse>

    @GET("api/v1/passwords/raw/{blob}")
    @Streaming
    suspend fun rawPassword(@Path("blob") blob: String): Response<ResponseBody>

    @Multipart
    @POST("api/v1/passwords/upload")
    suspend fun uploadPassword(@Part file: MultipartBody.Part): Response<UploadResponse>

    @GET("api/v1/files/store")
    suspend fun filesStore(): Response<StoreResponse>

    @PUT("api/v1/files/store")
    suspend fun filesStorePut(@Body body: StorePutRequest): Response<StoreResponse>

    // Public share links (owner CRUD). The share key lives only in the link fragment.
    @POST("api/v1/files/shares")
    suspend fun createFileShare(@Body body: de.ledgerline.app.data.remote.dto.ShareCreateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse>

    @PUT("api/v1/files/shares/{token}")
    suspend fun updateFileShare(@Path("token") token: String, @Body body: de.ledgerline.app.data.remote.dto.ShareUpdateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse>

    @DELETE("api/v1/files/shares/{token}")
    suspend fun deleteFileShare(@Path("token") token: String): Response<Unit>

    @POST("api/v1/gallery/shares")
    suspend fun createGalleryShare(@Body body: de.ledgerline.app.data.remote.dto.ShareCreateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse>

    @PUT("api/v1/gallery/shares/{token}")
    suspend fun updateGalleryShare(@Path("token") token: String, @Body body: de.ledgerline.app.data.remote.dto.ShareUpdateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse>

    @DELETE("api/v1/gallery/shares/{token}")
    suspend fun deleteGalleryShare(@Path("token") token: String): Response<Unit>

    @PUT("api/v1/store")
    suspend fun putStore(@Body body: StorePutRequest): Response<StoreResponse>

    @GET("api/v1/files/usage")
    suspend fun filesUsage(): Response<UsageResponse>

    @GET("api/v1/gallery/store")
    suspend fun galleryStore(): Response<StoreResponse>

    @GET("api/v1/gallery/raw/{blob}")
    @Streaming
    suspend fun galleryRaw(@Path("blob") blob: String): Response<ResponseBody>

    // Batched blob fetch: framed concat of up to 512 blobs' ciphertext (fewer round-trips
    // for thumbnail prefetch). Frame = u32le(idLen) + id + u32le(size) + ciphertext.
    @POST("api/v1/gallery/raw-batch")
    @Streaming
    suspend fun galleryRawBatch(@Body body: ReconcileRequest): Response<ResponseBody>

    @GET("api/v1/gallery/usage")
    suspend fun galleryUsage(): Response<UsageResponse>

    @Multipart
    @POST("api/v1/gallery/upload")
    suspend fun galleryUpload(@Part file: MultipartBody.Part): Response<UploadResponse>

    @POST("api/v1/gallery/upload/init")
    suspend fun galleryUploadInit(@Body body: de.ledgerline.app.data.remote.dto.UploadInitRequest): Response<de.ledgerline.app.data.remote.dto.UploadInitResponse>

    @Multipart
    @POST("api/v1/gallery/upload/part")
    suspend fun galleryUploadPart(
        @Part("token") token: okhttp3.RequestBody,
        @Part("part") part: okhttp3.RequestBody,
        @Part chunk: MultipartBody.Part,
    ): Response<de.ledgerline.app.data.remote.dto.UploadPartResponse>

    @POST("api/v1/gallery/upload/complete")
    suspend fun galleryUploadComplete(@Body body: de.ledgerline.app.data.remote.dto.UploadCompleteRequest): Response<UploadResponse>

    @POST("api/v1/gallery/upload/abort")
    suspend fun galleryUploadAbort(@Body body: de.ledgerline.app.data.remote.dto.UploadAbortRequest): Response<Unit>

    @Multipart
    @POST("api/v1/gallery/process")
    suspend fun galleryProcess(@Part file: MultipartBody.Part): Response<ProcessResponse>

    @PUT("api/v1/gallery/store")
    suspend fun galleryStorePut(@Body body: StorePutRequest): Response<StoreResponse>

    @DELETE("api/v1/gallery/blob/{blob}")
    suspend fun deleteGalleryBlob(@Path("blob") blob: String): Response<Unit>

    @POST("api/v1/gallery/embed-text")
    suspend fun embedText(@Body body: EmbedTextRequest): Response<EmbedTextResponse>

    @GET("api/v1/gallery/reverse")
    suspend fun galleryReverse(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
    ): Response<ReverseResponse>

    /** Snap a `lat,lng;lat,lng;…` waypoint string to a routed path (Explore tour planning). */
    @GET("api/v1/maps/route")
    suspend fun mapsRoute(@Query("points") points: String): Response<de.ledgerline.app.data.remote.dto.MapsRouteResponse>

    // --- Contacts avatar blobs (records themselves live in the /store manifest) ---

    @GET("api/v1/contacts/usage")
    suspend fun contactsUsage(): Response<UsageResponse>

    // Deferred: orphaned-blob garbage-collection not yet wired (CLAUDE.md §6).
    @POST("api/v1/contacts/blobs/reconcile")
    suspend fun contactsReconcile(@Body body: ReconcileRequest): Response<ReconcileResponse>

    @Multipart
    @POST("api/v1/contacts/upload")
    suspend fun contactsUpload(@Part file: MultipartBody.Part): Response<UploadResponse>

    @GET("api/v1/contacts/raw/{blob}")
    @Streaming
    suspend fun contactsRaw(@Path("blob") blob: String): Response<ResponseBody>

    @DELETE("api/v1/contacts/blob/{blob}")
    suspend fun deleteContactBlob(@Path("blob") blob: String): Response<Unit>
}
