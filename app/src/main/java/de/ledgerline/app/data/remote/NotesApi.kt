package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.notes.NoteAttachmentResponse
import de.ledgerline.app.domain.model.notes.NoteFolderResponse
import de.ledgerline.app.domain.model.notes.NoteResponse
import de.ledgerline.app.domain.model.notes.NotesData
import de.ledgerline.app.domain.model.notes.NotesSearchResponse
import de.ledgerline.app.domain.model.notes.NotesTrash
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The Notes module REST surface (`module:notes`, plaintext-relational, Markdown). Snapshot read +
 * per-record CRUD with an optimistic `version` (PUT → 409), soft-delete recycle bin, folder tree,
 * pin/favorite and full-text search — mirrors the Files/Todos data model. Write bodies are free-form
 * [JsonObject] (NoteInput / NoteFolderInput). No client crypto — plaintext over TLS.
 */
interface NotesApi {
    @GET("api/v1/notes/data")
    suspend fun data(): Response<NotesData>

    @GET("api/v1/notes/trash")
    suspend fun trash(): Response<NotesTrash>

    @GET("api/v1/notes/search")
    suspend fun search(@Query("q") q: String): Response<NotesSearchResponse>

    @POST("api/v1/notes")
    suspend fun create(@Body body: JsonObject): Response<NoteResponse>

    @GET("api/v1/notes/{id}")
    suspend fun show(@Path("id") id: Int): Response<NoteResponse>

    @PUT("api/v1/notes/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: JsonObject): Response<NoteResponse>

    @DELETE("api/v1/notes/{id}")
    suspend fun delete(@Path("id") id: Int): Response<Unit>

    @PATCH("api/v1/notes/{id}/favorite")
    suspend fun favorite(@Path("id") id: Int, @Body body: JsonObject): Response<Unit>

    @PATCH("api/v1/notes/{id}/pin")
    suspend fun pin(@Path("id") id: Int, @Body body: JsonObject): Response<Unit>

    @POST("api/v1/notes/{id}/restore")
    suspend fun restore(@Path("id") id: Int): Response<Unit>

    @DELETE("api/v1/notes/{id}/force")
    suspend fun force(@Path("id") id: Int): Response<Unit>

    // ---- Folders ----
    @POST("api/v1/notes/folders")
    suspend fun createFolder(@Body body: JsonObject): Response<NoteFolderResponse>

    @PUT("api/v1/notes/folders/{id}")
    suspend fun updateFolder(@Path("id") id: Int, @Body body: JsonObject): Response<NoteFolderResponse>

    @DELETE("api/v1/notes/folders/{id}")
    suspend fun deleteFolder(@Path("id") id: Int): Response<Unit>

    @POST("api/v1/notes/folders/{id}/restore")
    suspend fun restoreFolder(@Path("id") id: Int): Response<Unit>

    // ---- Attachments (MIME allowlist pdf/jpg/png/webp/gif; bytes served sandboxed) ----
    @Multipart
    @POST("api/v1/notes/{note}/attachments")
    suspend fun attach(
        @Path("note") note: Int,
        @Part file: MultipartBody.Part,
        @Part("name") name: RequestBody?,
    ): Response<NoteAttachmentResponse>

    @GET("api/v1/notes/{note}/attachments/{attachment}/raw")
    @Streaming
    suspend fun attachmentRaw(@Path("note") note: Int, @Path("attachment") attachment: Int): Response<ResponseBody>

    @DELETE("api/v1/notes/{note}/attachments/{attachment}")
    suspend fun deleteAttachment(@Path("note") note: Int, @Path("attachment") attachment: Int): Response<Unit>

    /** Stream a note as a `text/markdown` file (YAML frontmatter with title/tags + body). */
    @GET("api/v1/notes/{note}/export")
    @Streaming
    suspend fun export(@Path("note") note: Int): Response<ResponseBody>
}
