package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.files.FileEntry
import de.ledgerline.app.domain.model.files.FileFolder
import de.ledgerline.app.domain.model.files.FileLabel
import de.ledgerline.app.domain.model.files.FileVersion
import de.ledgerline.app.domain.model.files.FilesData
import de.ledgerline.app.domain.model.files.FilesStats
import de.ledgerline.app.domain.model.files.FilesTrash
import de.ledgerline.app.domain.model.files.FolderShareView
import de.ledgerline.app.domain.model.files.ShareView
import de.ledgerline.app.domain.model.files.SharedBrowse
import de.ledgerline.app.domain.model.files.SharedWithMe
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
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Single-record wrappers the files endpoints return (`{ file: … }`, etc.). */
@Serializable data class FileResponse(val file: FileEntry)
@Serializable data class FolderResponse(val folder: FileFolder)
@Serializable data class LabelResponse(val label: FileLabel)
@Serializable data class LabelsResponse(val labels: List<FileLabel> = emptyList())
@Serializable data class FoldersResponse(val folders: List<FileFolder> = emptyList())
@Serializable data class VersionsResponse(val versions: List<FileVersion> = emptyList())
@Serializable data class FilesSearchResponse(val files: List<FileEntry> = emptyList())
@Serializable data class EmptyTrashResult(val deleted: Int = 0)
@Serializable data class ChunkInitResult(val id: String = "", val partSize: Long = 8_388_608)
@Serializable data class ChunkPartResult(val ok: Boolean = false, val index: Int = 0)
@Serializable data class ShareResponse(val share: ShareView)
@Serializable data class FolderShareResponse(val share: FolderShareView)
@Serializable data class FolderSharesResponse(val shares: List<FolderShareView> = emptyList())
@Serializable data class SharedWithMeResponse(val shares: List<SharedWithMe> = emptyList())

/**
 * The plaintext-relational Files REST surface (server pivot v1.5xx). Folders/files/labels are plain
 * owner-scoped rows; bytes stream as octet-stream. Metadata mutations carry an optimistic-concurrency
 * `version` in a free-form [JsonObject] body (PUT → 409 `{error, version}`); deletes are soft (trash)
 * with `/restore` + `/force`. No client crypto — payloads are plaintext over TLS.
 */
interface FilesApi {
    @GET("api/v1/files/data")
    suspend fun filesData(): Response<FilesData>

    @GET("api/v1/files/trash")
    suspend fun trash(): Response<FilesTrash>

    @GET("api/v1/files/search")
    suspend fun search(@Query("q") q: String): Response<FilesSearchResponse>

    @GET("api/v1/files/stats")
    suspend fun stats(): Response<FilesStats>

    // ---- Download (binary) ----
    @GET("api/v1/files/entries/{id}/raw")
    @Streaming
    suspend fun raw(@Path("id") id: Int, @Query("download") download: Int = 0): Response<ResponseBody>

    @GET("api/v1/files/entries/{id}/thumb")
    @Streaming
    suspend fun thumb(@Path("id") id: Int): Response<ResponseBody>

    // ---- Upload ----
    @Multipart
    @POST("api/v1/files/entries")
    suspend fun upload(@Part parts: List<MultipartBody.Part>): Response<FileResponse>

    @Multipart
    @POST("api/v1/files/entries/{id}/content")
    suspend fun replaceContent(@Path("id") id: Int, @Part file: MultipartBody.Part): Response<FileResponse>

    // Chunked (S3-style) upload for large files.
    @POST("api/v1/files/upload/chunk/init")
    suspend fun chunkInit(@Body body: JsonObject): Response<ChunkInitResult>

    @Multipart
    @POST("api/v1/files/upload/chunk/part")
    suspend fun chunkPart(
        @Part("id") id: RequestBody,
        @Part("index") index: RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<ChunkPartResult>

    @POST("api/v1/files/upload/chunk/complete")
    suspend fun chunkComplete(@Body body: JsonObject): Response<FileResponse>

    @POST("api/v1/files/upload/chunk/abort")
    suspend fun chunkAbort(@Body body: JsonObject): Response<OkBody>

    // ---- File metadata / lifecycle ----
    @PUT("api/v1/files/entries/{id}")
    suspend fun updateFile(@Path("id") id: Int, @Body body: JsonObject): Response<FileResponse>

    @POST("api/v1/files/entries/{id}/toggle")
    suspend fun toggle(@Path("id") id: Int, @Body body: JsonObject): Response<FileResponse>

    @DELETE("api/v1/files/entries/{id}")
    suspend fun deleteFile(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/files/entries/{id}/restore")
    suspend fun restoreFile(@Path("id") id: Int): Response<FileResponse>

    @DELETE("api/v1/files/entries/{id}/force")
    suspend fun forceFile(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/files/entries/trash/empty")
    suspend fun emptyTrash(): Response<EmptyTrashResult>

    // ---- Versions ----
    @GET("api/v1/files/entries/{id}/versions")
    suspend fun versions(@Path("id") id: Int): Response<VersionsResponse>

    @GET("api/v1/files/entries/{id}/versions/{version}/raw")
    @Streaming
    suspend fun versionRaw(@Path("id") id: Int, @Path("version") version: Int, @Query("download") download: Int = 0): Response<ResponseBody>

    @POST("api/v1/files/entries/{id}/versions/{version}/restore")
    suspend fun restoreVersion(@Path("id") id: Int, @Path("version") version: Int): Response<FileResponse>

    // ---- Folders ----
    @GET("api/v1/files/folders")
    suspend fun folders(): Response<FoldersResponse>

    @POST("api/v1/files/folders")
    suspend fun createFolder(@Body body: JsonObject): Response<FolderResponse>

    @PUT("api/v1/files/folders/{id}")
    suspend fun renameFolder(@Path("id") id: Int, @Body body: JsonObject): Response<FolderResponse>

    @POST("api/v1/files/folders/{id}/move")
    suspend fun moveFolder(@Path("id") id: Int, @Body body: JsonObject): Response<FolderResponse>

    @DELETE("api/v1/files/folders/{id}")
    suspend fun deleteFolder(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/files/folders/{id}/restore")
    suspend fun restoreFolder(@Path("id") id: Int): Response<FolderResponse>

    @DELETE("api/v1/files/folders/{id}/force")
    suspend fun forceFolder(@Path("id") id: Int): Response<OkBody>

    // ---- Labels ----
    @GET("api/v1/files/labels")
    suspend fun labels(): Response<LabelsResponse>

    @POST("api/v1/files/labels")
    suspend fun createLabel(@Body body: JsonObject): Response<LabelResponse>

    @PUT("api/v1/files/labels/{id}")
    suspend fun updateLabel(@Path("id") id: Int, @Body body: JsonObject): Response<LabelResponse>

    @DELETE("api/v1/files/labels/{id}")
    suspend fun deleteLabel(@Path("id") id: Int): Response<OkBody>

    @POST("api/v1/files/entries/{id}/labels")
    suspend fun setFileLabels(@Path("id") id: Int, @Body body: JsonObject): Response<FileResponse>

    // ---- Bulk ZIP (binary) ----
    @POST("api/v1/files/zip")
    @Streaming
    suspend fun zip(@Body body: JsonObject): Response<ResponseBody>

    // ---- Sharing: public links (owner side) ----
    @POST("api/v1/files/rel-shares")
    suspend fun createShare(@Body body: JsonObject): Response<ShareResponse>

    @PUT("api/v1/files/rel-shares/{id}")
    suspend fun updateShare(@Path("id") id: Int, @Body body: JsonObject): Response<ShareResponse>

    @DELETE("api/v1/files/rel-shares/{id}")
    suspend fun deleteShare(@Path("id") id: Int): Response<OkBody>

    // ---- Sharing: cross-user folder shares ----
    @GET("api/v1/files/folder-shares")
    suspend fun folderShares(): Response<FolderSharesResponse>

    @POST("api/v1/files/folder-shares")
    suspend fun createFolderShare(@Body body: JsonObject): Response<FolderShareResponse>

    @PUT("api/v1/files/folder-shares/{id}/members")
    suspend fun updateFolderShareMember(@Path("id") id: Int, @Body body: JsonObject): Response<FolderShareResponse>

    @HTTP(method = "DELETE", path = "api/v1/files/folder-shares/{id}/members", hasBody = true)
    suspend fun removeFolderShareMember(@Path("id") id: Int, @Body body: JsonObject): Response<OkBody>

    @DELETE("api/v1/files/folder-shares/{id}")
    suspend fun deleteFolderShare(@Path("id") id: Int): Response<OkBody>

    // ---- Sharing: shared-with-me (member side) ----
    @GET("api/v1/shared-with-me")
    suspend fun sharedWithMe(): Response<SharedWithMeResponse>

    @GET("api/v1/shared-with-me/{id}")
    suspend fun browseShared(@Path("id") id: Int): Response<SharedBrowse>

    @GET("api/v1/shared-with-me/{id}/files/{file}/raw")
    @Streaming
    suspend fun sharedRaw(@Path("id") id: Int, @Path("file") file: Int, @Query("download") download: Int = 0): Response<ResponseBody>

    @Multipart
    @POST("api/v1/shared-with-me/{id}/upload")
    suspend fun sharedUpload(@Path("id") id: Int, @Part parts: List<MultipartBody.Part>): Response<FileResponse>

    @PUT("api/v1/shared-with-me/{id}/files/{file}")
    suspend fun sharedRename(@Path("id") id: Int, @Path("file") file: Int, @Body body: JsonObject): Response<FileResponse>

    @DELETE("api/v1/shared-with-me/{id}/files/{file}")
    suspend fun sharedDelete(@Path("id") id: Int, @Path("file") file: Int): Response<OkBody>
}
