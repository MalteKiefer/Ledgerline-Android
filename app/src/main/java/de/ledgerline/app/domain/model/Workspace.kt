package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Every workspace record carries a `@Transient raw` — the original decrypted JsonObject captured
// on load — so a save re-emits every Web/iOS field this Kotlin model doesn't know (no cross-client
// data loss). See [de.ledgerline.app.data.WorkspaceRecordCodec]; mirrors Files/Gallery. `raw` is
// `@Transient` so kotlinx never (de)serialises it as a nested key.

@Serializable
data class WorkspaceManifest(
    val v: Int = 1,
    val notes: List<Note> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarkFolders: List<NamedFolder> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val todoLists: List<TodoList> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val fileFolders: List<NamedFolder> = emptyList(),
    val contacts: List<Contact> = emptyList(),
)

@Serializable
data class Note(
    val id: String = "", val title: String = "", val content: String = "",
    val pinned: Boolean = false,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val updated: String? = null,
    val tags: List<String> = emptyList(),
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Bookmark(
    val id: String = "", val folderId: String? = null, val title: String = "", val url: String = "",
    val description: String = "", val favorite: Boolean = false, val readLater: Boolean = false,
    val read: Boolean = false,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val tags: List<String> = emptyList(),
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/**
 * A named folder (bookmark or file tree). The app field [parent] is the parent-folder id; on the
 * wire it is **`parentId`** for bookmark folders (web `bookmarks.js`) and **`parent`** for file
 * folders — the per-module codecs map it. `color`/`icon` are Android-only extras.
 */
@Serializable
data class NamedFolder(
    val id: String = "", val name: String = "", val parent: String? = null,
    val color: String = "", val icon: String = "",
    /** Public share-link state (owner-side, folder shares); null = not shared. */
    val share: ShareInfo? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TodoList(
    val id: String = "", val name: String = "",
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TodoItem(
    val id: String = "", val listId: String? = null, val title: String = "", val description: String = "",
    val url: String = "", val priority: String = "normal", val marked: Boolean = false,
    val due: String = "", val done: Boolean = false,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val tags: List<String> = emptyList(),
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FileEntry(
    val id: String = "", val blob: String = "", val encFileKey: String = "", val name: String = "",
    val mime: String = "", val size: Long = 0, val folder: String? = null,
    val created: String? = null,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val versions: List<FileVersion> = emptyList(),
    /** Public share-link state (owner-side); null = not shared. Byte-shape = web `src.share`. */
    val share: ShareInfo? = null,
)

/**
 * Owner-side public-share record persisted in the sealed store (byte-compatible with the
 * web client's `src.share` / `al.share`): the share [token] and the 32-byte share key [sk]
 * (base64) so the link can be re-copied/revoked. `sk` NEVER leaves the device except in the
 * link fragment. [kind] is files-only (`"file"|"folder"`); [allowDownload] is gallery-only.
 */
@Serializable
data class ShareInfo(
    val token: String = "",
    val sk: String = "",
    val kind: String? = null,
    val allowDownload: Boolean? = null,
    val hasPassword: Boolean = false,
    val expiresAt: String? = null,
    val created: String? = null,
)

@Serializable
data class FileVersion(
    val id: String = "", val blob: String = "", val encFileKey: String = "",
    val size: Long = 0, val mime: String = "", val name: String = "",
    val created: String? = null,
)

@Serializable
data class LabeledValue(val value: String = "", val type: String = "home")

@Serializable
data class PostalAddress(
    val street: String = "", val city: String = "", val region: String = "",
    val zip: String = "", val country: String = "", val type: String = "home",
)

/**
 * A zero-knowledge contact record. Lives in the sealed `/store` manifest; only the
 * optional avatar is a separate encrypted blob (`avatarRef`/`avatarKey`). Every field
 * is defaulted and tolerant so web-authored records decode cleanly; `_x` preserves
 * unknown vCard properties through a decode→encode round-trip (Phase-A integrity fix).
 */
@Serializable
data class Contact(
    val id: String = "",
    val uid: String? = null,        // vCard UID (urn:uuid) — preserved for round-trip/export
    val fn: String = "",            // formatted/display name
    val first: String = "", val last: String = "", val middle: String = "",
    val prefix: String = "", val suffix: String = "", val nickname: String = "",
    val org: String = "", val department: String = "", val title: String = "", val role: String = "",
    val vatId: String = "",         // VAT / tax id (web/iOS field; was dropped before)
    val emails: List<LabeledValue> = emptyList(),
    val phones: List<LabeledValue> = emptyList(),
    val impp: List<LabeledValue> = emptyList(),
    val urls: List<LabeledValue> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val bday: String = "", val anniversary: String = "",
    val note: String = "",
    val categories: List<String> = emptyList(),
    val favorite: Boolean = false,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val avatarRef: String? = null, val avatarKey: String? = null,
    val bdayNotified: Int? = null, val annivNotified: Int? = null,
    // Link to a gallery Person (bidirectional; the person stores contactId back).
    val personId: String? = null,
    val personName: String? = null,
    val _x: List<JsonElement> = emptyList(),  // preserve unknown vCard props round-trip
    val updated: String? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/** The decrypted manifest plus the server version (kept for Phase-3 writes). */
data class Workspace(val manifest: WorkspaceManifest, val version: Int)

// ---------------------------------------------------------------------------
//  Store v3 — per-module sealed manifests
// ---------------------------------------------------------------------------
// The server removed the monolith `/store`; each workspace module now has its own
// sealed store `GET/PUT /api/v1/store/{module}` (`{ciphertext, version}`). Each
// module manifest carries `v:3` and exactly the top-level keys the web client
// (`resources/js/shared/module-store.js` MODULE_BLANKS) emits — the byte-contract.
// The app still works against the aggregate [WorkspaceManifest]; these are the
// per-module wire shapes the repository fans out to. Files/gallery are sharded and
// migrated separately (see CLAUDE.md §14 R1).

@Serializable
data class NotesManifest(val v: Int = 3, val notes: List<Note> = emptyList())

@Serializable
data class TodosManifest(
    val v: Int = 3,
    val todos: List<TodoItem> = emptyList(),
    val todoLists: List<TodoList> = emptyList(),
)

@Serializable
data class BookmarksManifest(
    val v: Int = 3,
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarkFolders: List<NamedFolder> = emptyList(),
)

@Serializable
data class ContactsManifest(val v: Int = 3, val contacts: List<Contact> = emptyList())
