package de.ledgerline.app.domain.model.notes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A folder in the notes tree (plaintext-relational, owner-scoped). Additive/lenient decode. */
@Serializable
data class NoteFolder(
    val id: Int = 0,
    @SerialName("parent_id") val parentId: Int? = null,
    val name: String = "",
    val color: String? = null,
    val position: Int = 0,
    val version: Int = 0,
)

/** Lightweight note row for the list view — no body. */
@Serializable
data class NoteRow(
    val id: Int = 0,
    @SerialName("note_folder_id") val folderId: Int? = null,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A full note (editor view) — includes the plaintext Markdown body. */
@Serializable
data class Note(
    val id: Int = 0,
    @SerialName("note_folder_id") val folderId: Int? = null,
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    val version: Int = 0,
)

/** A tag with its usage count (from the aggregate in `/notes/data`). */
@Serializable
data class NoteTag(val name: String = "", val count: Int = 0)

/** `GET /notes/data` snapshot: folder tree + note rows (no body) + tag aggregate. */
@Serializable
data class NotesData(
    val folders: List<NoteFolder> = emptyList(),
    val notes: List<NoteRow> = emptyList(),
    val tags: List<NoteTag> = emptyList(),
)

/** A trashed folder stub (`/notes/trash` returns only id + name for folders). */
@Serializable
data class TrashedFolder(val id: Int = 0, val name: String = "")

/** `GET /notes/trash`: soft-deleted notes + folders. */
@Serializable
data class NotesTrash(
    val notes: List<NoteRow> = emptyList(),
    val folders: List<TrashedFolder> = emptyList(),
)

@Serializable data class NoteResponse(val note: Note = Note())
@Serializable data class NoteFolderResponse(val folder: NoteFolder = NoteFolder())
@Serializable data class NotesSearchResponse(val notes: List<NoteRow> = emptyList())
