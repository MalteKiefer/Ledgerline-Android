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
    /** Short plain-text preview of the Markdown body (list cards). */
    val excerpt: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A note linking to the current one, with a short body snippet (wikilink backlink). */
@Serializable
data class NoteBacklink(val id: Int = 0, val title: String = "", val snippet: String = "")

/** A file/image attached to a note; bytes served sandboxed via `.../raw`. */
@Serializable
data class NoteAttachment(
    val id: Int = 0,
    val name: String = "",
    val mime: String? = null,
    val size: Long = 0,
)

/** A full note (editor view) — includes the plaintext Markdown body + inbound wikilink backlinks. */
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
    // Present on show/store/update: notes that link to this one via [[wikilink]] syntax.
    val backlinks: List<NoteBacklink> = emptyList(),
    // Present on show: file/image attachments.
    val attachments: List<NoteAttachment> = emptyList(),
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
@Serializable data class NoteAttachmentResponse(val attachment: NoteAttachment = NoteAttachment())
@Serializable data class NoteFolderResponse(val folder: NoteFolder = NoteFolder())
@Serializable data class NotesSearchResponse(val notes: List<NoteRow> = emptyList())
