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

    @GET("api/v1/auth/pair")
    suspend fun pollPair(@Query("code") code: String): Response<PairPollResponse>

    @GET("api/v1/me")
    suspend fun me(): Response<MeResponse>

    @GET("api/v1/vault")
    suspend fun vault(): Response<VaultResponse>

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

    @DELETE("api/v1/files/blob/{blob}")
    suspend fun deleteBlob(@Path("blob") blob: String): Response<Unit>

    @PUT("api/v1/store")
    suspend fun putStore(@Body body: StorePutRequest): Response<StoreResponse>

    @GET("api/v1/files/usage")
    suspend fun filesUsage(): Response<UsageResponse>

    @GET("api/v1/gallery/store")
    suspend fun galleryStore(): Response<StoreResponse>

    @GET("api/v1/gallery/raw/{blob}")
    @Streaming
    suspend fun galleryRaw(@Path("blob") blob: String): Response<ResponseBody>

    @GET("api/v1/gallery/usage")
    suspend fun galleryUsage(): Response<UsageResponse>

    @Multipart
    @POST("api/v1/gallery/upload")
    suspend fun galleryUpload(@Part file: MultipartBody.Part): Response<UploadResponse>

    @Multipart
    @POST("api/v1/gallery/process")
    suspend fun galleryProcess(@Part file: MultipartBody.Part): Response<ProcessResponse>

    @PUT("api/v1/gallery/store")
    suspend fun galleryStorePut(@Body body: StorePutRequest): Response<StoreResponse>

    @DELETE("api/v1/gallery/blob/{blob}")
    suspend fun deleteGalleryBlob(@Path("blob") blob: String): Response<Unit>

    @POST("api/v1/gallery/embed-text")
    suspend fun embedText(@Body body: EmbedTextRequest): Response<EmbedTextResponse>

    // --- Contacts avatar blobs (records themselves live in the /store manifest) ---

    @GET("api/v1/contacts/usage")
    suspend fun contactsUsage(): Response<UsageResponse>

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
