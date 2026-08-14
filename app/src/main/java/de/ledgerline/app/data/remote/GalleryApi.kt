package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.gallery.GalleryAlbum
import de.ledgerline.app.domain.model.gallery.GalleryData
import de.ledgerline.app.domain.model.gallery.GalleryExif
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

@Serializable data class GalleryPhotoResponse(val photo: GalleryPhoto)
@Serializable data class GalleryChunkInit(val id: String = "", val partSize: Long = 8_388_608)
@Serializable data class GalleryChunkPart(val ok: Boolean = false, val index: Int = 0)
@Serializable data class GalleryAlbumsResponse(val albums: List<GalleryAlbum> = emptyList())
@Serializable data class GalleryAlbumResponse(val album: GalleryAlbum)

/**
 * The Gallery module REST surface — Phase 1 (MVP viewing): timeline read, sandboxed bytes
 * (thumb/preview/raw), EXIF, favorite, non-invasive edit, whole + chunked upload, download, and the
 * soft-delete recycle bin. Later phases (albums, sharing, ML/people, backup) extend this interface.
 */
interface GalleryApi {
    @GET("api/v1/gallery/data")
    suspend fun data(
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
        @Query("cursor_ym") cursorYm: String? = null,
        @Query("album_id") albumId: Int? = null,
        @Query("archived") archived: Boolean? = null,
    ): Response<GalleryData>

    @GET("api/v1/gallery/dates")
    suspend fun dates(
        @Query("album_id") albumId: Int? = null,
        @Query("archived") archived: Boolean? = null,
    ): Response<de.ledgerline.app.domain.model.gallery.GalleryDates>

    @GET("api/v1/gallery/trash")
    suspend fun trash(): Response<GalleryData>

    // ---- Bytes (sandboxed) ----
    @GET("api/v1/gallery/{id}/thumb")
    @Streaming
    suspend fun thumb(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/v1/gallery/{id}/preview")
    @Streaming
    suspend fun preview(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/v1/gallery/{id}/raw")
    @Streaming
    suspend fun raw(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/v1/gallery/{id}/download")
    @Streaming
    suspend fun download(@Path("id") id: Int, @Query("variant") variant: String? = null): Response<ResponseBody>

    @GET("api/v1/gallery/{id}/exif")
    suspend fun exif(@Path("id") id: Int): Response<GalleryExif>

    /** Web-MP4 rendition (or original) for playback; Range honoured. */
    @GET("api/v1/gallery/{id}/play")
    @Streaming
    suspend fun play(@Path("id") id: Int): Response<ResponseBody>

    /** A Live Photo's motion clip (best-effort, stored as received). */
    @GET("api/v1/gallery/{id}/motion")
    @Streaming
    suspend fun motion(@Path("id") id: Int): Response<ResponseBody>

    // ---- Upload (whole + chunked) ----
    @Multipart
    @POST("api/v1/gallery")
    suspend fun upload(@Part file: MultipartBody.Part): Response<GalleryPhotoResponse>

    @POST("api/v1/gallery/chunk/init")
    suspend fun chunkInit(@Body body: JsonObject): Response<GalleryChunkInit>

    @Multipart
    @POST("api/v1/gallery/chunk/part")
    suspend fun chunkPart(
        @Part("id") id: RequestBody,
        @Part("index") index: RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<GalleryChunkPart>

    @POST("api/v1/gallery/chunk/complete")
    suspend fun chunkComplete(@Body body: JsonObject): Response<GalleryPhotoResponse>

    @POST("api/v1/gallery/chunk/abort")
    suspend fun chunkAbort(@Body body: JsonObject): Response<OkBody>

    // ---- Mutations / lifecycle ----
    @PATCH("api/v1/gallery/{id}/favorite")
    suspend fun favorite(@Path("id") id: Int, @Body body: JsonObject): Response<GalleryPhotoResponse>

    @retrofit2.http.PUT("api/v1/gallery/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: JsonObject): Response<GalleryPhotoResponse>

    @PATCH("api/v1/gallery/{id}/archive")
    suspend fun archive(@Path("id") id: Int, @Body body: JsonObject): Response<GalleryPhotoResponse>

    @POST("api/v1/gallery/bulk-archive")
    suspend fun bulkArchive(@Body body: JsonObject): Response<OkBody>

    @DELETE("api/v1/gallery/{id}")
    suspend fun destroy(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/gallery/bulk-destroy")
    suspend fun bulkDestroy(@Body body: JsonObject): Response<OkBody>

    @POST("api/v1/gallery/{id}/restore")
    suspend fun restore(@Path("id") id: Int): Response<GalleryPhotoResponse>

    @DELETE("api/v1/gallery/{id}/force")
    suspend fun force(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/gallery/trash/empty")
    suspend fun emptyTrash(): Response<OkBody>

    // ---- Albums ----
    @GET("api/v1/gallery/albums")
    suspend fun albums(): Response<GalleryAlbumsResponse>

    @POST("api/v1/gallery/albums")
    suspend fun createAlbum(@Body body: JsonObject): Response<GalleryAlbumResponse>

    @retrofit2.http.PUT("api/v1/gallery/albums/{id}")
    suspend fun updateAlbum(@Path("id") id: Int, @Body body: JsonObject): Response<GalleryAlbumResponse>

    @DELETE("api/v1/gallery/albums/{id}")
    suspend fun deleteAlbum(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/gallery/albums/{id}/photos")
    suspend fun attachToAlbum(@Path("id") id: Int, @Body body: JsonObject): Response<OkBody>

    @HTTP(method = "DELETE", path = "api/v1/gallery/albums/{id}/photos", hasBody = true)
    suspend fun detachFromAlbum(@Path("id") id: Int, @Body body: JsonObject): Response<OkBody>
}
