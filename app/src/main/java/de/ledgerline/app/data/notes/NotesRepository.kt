package de.ledgerline.app.data.notes

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.NotesApi
import de.ledgerline.app.domain.model.notes.Note
import de.ledgerline.app.domain.model.notes.NoteFolder
import de.ledgerline.app.domain.model.notes.NoteRow
import de.ledgerline.app.domain.model.notes.NoteTag
import de.ledgerline.app.domain.model.notes.NotesTrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data layer for the Notes module. Online-only (mirrors Files/Todos): [folders] is the owner's folder
 * tree, [notes] the lightweight row list (no body), [tags] the aggregate. The full body is fetched on
 * demand for the editor ([note]). Writes are per-record REST with an optimistic `version`; on success
 * we reload so pin/favorite ordering and tag counts stay exact.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
) {
    private val _folders = MutableStateFlow<List<NoteFolder>>(emptyList())
    val folders: StateFlow<List<NoteFolder>> = _folders.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteRow>>(emptyList())
    val notes: StateFlow<List<NoteRow>> = _notes.asStateFlow()

    private val _tags = MutableStateFlow<List<NoteTag>>(emptyList())
    val tags: StateFlow<List<NoteTag>> = _tags.asStateFlow()

    private fun api(): NotesApi {
        val s = sessionHolder.get() ?: error("no session")
        return NetworkFactory.createNotes(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin)
    }

    /** Refresh the folder tree + note rows + tag aggregate. Pinned first, then most-recently-updated. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val d = api().data().body() ?: return@runCatching false
            _folders.value = d.folders.sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
            _notes.value = d.notes.sortedWith(
                compareByDescending<NoteRow> { it.pinned }.thenByDescending { it.updatedAt ?: "" },
            )
            _tags.value = d.tags.sortedByDescending { it.count }
            true
        }.getOrDefault(false)
    }

    /** Full note (with body) for the editor. Null on failure. */
    suspend fun note(id: Int): Note? = withContext(Dispatchers.IO) {
        runCatching { api().show(id).takeIf { it.isSuccessful }?.body()?.note }.getOrNull()
    }

    suspend fun create(title: String, body: String, tags: List<String>, folderId: Int?): Note? =
        withContext(Dispatchers.IO) {
            val res = runCatching { api().create(noteBody(title, body, tags, folderId, version = null)) }.getOrNull()
            val note = res?.takeIf { it.isSuccessful }?.body()?.note
            if (note != null) load()
            note
        }

    /** Update a note (optimistic). Returns the updated note, or null on failure / 409 conflict. */
    suspend fun update(id: Int, title: String, body: String, tags: List<String>, folderId: Int?, version: Int): Note? =
        withContext(Dispatchers.IO) {
            val res = runCatching { api().update(id, noteBody(title, body, tags, folderId, version)) }.getOrNull()
            val note = res?.takeIf { it.isSuccessful }?.body()?.note
            if (note != null) load()
            note
        }

    suspend fun delete(id: Int): Boolean = mutate { api().delete(id) }
    suspend fun setFavorite(id: Int, on: Boolean): Boolean = mutate { api().favorite(id, buildJsonObject { put("favorite", on) }) }
    suspend fun setPinned(id: Int, on: Boolean): Boolean = mutate { api().pin(id, buildJsonObject { put("pinned", on) }) }
    suspend fun restore(id: Int): Boolean = mutate { api().restore(id) }
    suspend fun force(id: Int): Boolean = mutate { api().force(id) }

    suspend fun createFolder(name: String, parentId: Int?): Boolean = mutate {
        api().createFolder(buildJsonObject { put("name", name.trim()); put("parent_id", parentId?.let { JsonPrimitive(it) } ?: JsonNull) })
    }
    suspend fun renameFolder(id: Int, name: String, version: Int): Boolean = mutate {
        api().updateFolder(id, buildJsonObject { put("name", name.trim()); put("version", version) })
    }
    suspend fun deleteFolder(id: Int): Boolean = mutate { api().deleteFolder(id) }
    suspend fun restoreFolder(id: Int): Boolean = mutate { api().restoreFolder(id) }

    suspend fun trash(): NotesTrash? = withContext(Dispatchers.IO) {
        runCatching { api().trash().takeIf { it.isSuccessful }?.body() }.getOrNull()
    }

    // ---- Attachments ----
    /** Upload a file/image to a note. Returns the attachment metadata, or null on failure. */
    suspend fun attach(noteId: Int, bytes: ByteArray, name: String, mime: String?): de.ledgerline.app.domain.model.notes.NoteAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val media = (mime ?: "application/octet-stream").toMediaTypeOrNull()
                val part = okhttp3.MultipartBody.Part.createFormData("file", name, bytes.toRequestBody(media))
                val namePart = name.toRequestBody("text/plain".toMediaTypeOrNull())
                api().attach(noteId, part, namePart).takeIf { it.isSuccessful }?.body()?.attachment
            }.getOrNull()
        }

    /** Download an attachment's bytes into the cache dir and return the file (for DocOpener). */
    suspend fun attachmentToCache(cacheDir: java.io.File, noteId: Int, att: de.ledgerline.app.domain.model.notes.NoteAttachment): java.io.File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val res = api().attachmentRaw(noteId, att.id)
                val body = res.takeIf { it.isSuccessful }?.body() ?: return@runCatching null
                val out = java.io.File(cacheDir, "note_att_${att.id}_${att.name}")
                body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                out
            }.getOrNull()
        }

    /** Embed an existing owner-scoped Files image or video into a note without re-uploading. */
    suspend fun attachFromFile(noteId: Int, fileId: Int): de.ledgerline.app.domain.model.notes.NoteAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("source", "file")
                    put("id", fileId)
                }
                api().attachFrom(noteId, body).takeIf { it.isSuccessful }?.body()?.attachment
            }.getOrNull()
        }

    suspend fun deleteAttachment(noteId: Int, attId: Int): Boolean =
        withContext(Dispatchers.IO) { runCatching { api().deleteAttachment(noteId, attId).isSuccessful }.getOrDefault(false) }

    /** The note as Markdown bytes (YAML frontmatter + body) for a SAF export. Null on failure. */
    suspend fun exportMarkdown(noteId: Int): ByteArray? =
        withContext(Dispatchers.IO) { runCatching { api().export(noteId).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull() }

    suspend fun search(q: String): List<NoteRow> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        runCatching { api().search(q).takeIf { it.isSuccessful }?.body()?.notes.orEmpty() }.getOrDefault(emptyList())
    }

    fun clear() { _folders.value = emptyList(); _notes.value = emptyList(); _tags.value = emptyList() }

    /** Run a mutating call and reload the snapshot on success. */
    private suspend fun mutate(block: suspend () -> retrofit2.Response<*>): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { block().isSuccessful }.getOrDefault(false)
        if (ok) load()
        ok
    }

    private fun noteBody(title: String, body: String, tags: List<String>, folderId: Int?, version: Int?): JsonObject =
        buildJsonObject {
            put("title", title.trim())
            put("body", body)
            put("tags", buildJsonArray { tags.forEach { add(it) } })
            put("note_folder_id", folderId?.let { JsonPrimitive(it) } ?: JsonNull)
            version?.let { put("version", it) }
        }
}
