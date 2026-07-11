package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
)

@Serializable
data class Bookmark(
    val id: String = "", val folderId: String? = null, val title: String = "", val url: String = "",
    val description: String = "", val favorite: Boolean = false, val readLater: Boolean = false,
    val read: Boolean = false,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val tags: List<String> = emptyList(),
)

@Serializable
data class NamedFolder(
    val id: String = "", val name: String = "", val parent: String? = null,
    val color: String = "", val icon: String = "",
)

@Serializable
data class TodoList(val id: String = "", val name: String = "")

@Serializable
data class TodoItem(
    val id: String = "", val listId: String? = null, val title: String = "", val description: String = "",
    val url: String = "", val priority: String = "normal", val marked: Boolean = false,
    val due: String = "", val done: Boolean = false,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val tags: List<String> = emptyList(),
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
    val fn: String = "",            // formatted/display name
    val first: String = "", val last: String = "", val middle: String = "",
    val prefix: String = "", val suffix: String = "", val nickname: String = "",
    val org: String = "", val department: String = "", val title: String = "", val role: String = "",
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
    val _x: List<JsonElement> = emptyList(),  // preserve unknown vCard props round-trip
    val updated: String? = null,
)

/** The decrypted manifest plus the server version (kept for Phase-3 writes). */
data class Workspace(val manifest: WorkspaceManifest, val version: Int)
