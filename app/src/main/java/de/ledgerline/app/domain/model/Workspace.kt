package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

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
)

@Serializable
data class Note(
    val id: String = "", val title: String = "", val content: String = "",
    val pinned: Boolean = false, val trashed: Boolean = false, val updated: String? = null,
)

@Serializable
data class Bookmark(
    val id: String = "", val folderId: String? = null, val title: String = "", val url: String = "",
    val description: String = "", val favorite: Boolean = false, val readLater: Boolean = false, val trashed: Boolean = false,
)

@Serializable
data class NamedFolder(val id: String = "", val name: String = "", val parent: String? = null)

@Serializable
data class TodoList(val id: String = "", val name: String = "")

@Serializable
data class TodoItem(
    val id: String = "", val listId: String? = null, val title: String = "", val description: String = "",
    val url: String = "", val priority: String = "normal", val marked: Boolean = false,
    val due: String = "", val done: Boolean = false, val trashed: Boolean = false,
)

@Serializable
data class FileEntry(
    val id: String = "", val blob: String = "", val encFileKey: String = "", val name: String = "",
    val mime: String = "", val size: Long = 0, val folder: String? = null,
    val created: String? = null, val trashed: Boolean = false,
)

/** The decrypted manifest plus the server version (kept for Phase-3 writes). */
data class Workspace(val manifest: WorkspaceManifest, val version: Int)
