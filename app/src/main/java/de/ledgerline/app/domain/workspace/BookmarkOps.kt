package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.WorkspaceManifest

/**
 * Pure manifest transforms for bookmark & bookmark-folder management. Each returns a
 * new [WorkspaceManifest]; mutating an unknown id is a safe no-op. Mirrors the web
 * `vaultWorkspace` bookmark ops (see [TodoOps] for the same style).
 */
object BookmarkOps {

    fun addBookmark(
        m: WorkspaceManifest,
        id: String,
        url: String,
        title: String,
        description: String,
        folderId: String?,
        tags: List<String>,
    ): WorkspaceManifest {
        val bookmark = Bookmark(
            id = id,
            folderId = folderId,
            title = title.trim(),
            url = url.trim(),
            description = description.trim(),
            favorite = false,
            readLater = false,
            trashed = false,
            tags = tags,
        )
        return m.copy(bookmarks = m.bookmarks + bookmark)
    }

    fun editBookmark(
        m: WorkspaceManifest,
        id: String,
        url: String,
        title: String,
        description: String,
        folderId: String?,
        tags: List<String>,
    ): WorkspaceManifest = updateBookmark(m, id) {
        it.copy(
            url = url.trim(),
            title = title.trim(),
            description = description.trim(),
            folderId = folderId,
            tags = tags,
        )
    }

    fun toggleFavorite(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateBookmark(m, id) { it.copy(favorite = !it.favorite) }

    fun toggleReadLater(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateBookmark(m, id) { it.copy(readLater = !it.readLater) }

    fun trashBookmark(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateBookmark(m, id) { it.copy(trashed = true) }

    fun addFolder(m: WorkspaceManifest, id: String, name: String): WorkspaceManifest =
        m.copy(bookmarkFolders = m.bookmarkFolders + NamedFolder(id = id, name = name.trim()))

    fun renameFolder(m: WorkspaceManifest, id: String, name: String): WorkspaceManifest =
        m.copy(bookmarkFolders = m.bookmarkFolders.map { if (it.id == id) it.copy(name = name.trim()) else it })

    /** Remove the folder and orphan its bookmarks (`folderId == id` → `folderId = null`). */
    fun deleteFolder(m: WorkspaceManifest, id: String): WorkspaceManifest = m.copy(
        bookmarkFolders = m.bookmarkFolders.filterNot { it.id == id },
        bookmarks = m.bookmarks.map { if (it.folderId == id) it.copy(folderId = null) else it },
    )

    private inline fun updateBookmark(
        m: WorkspaceManifest,
        id: String,
        transform: (Bookmark) -> Bookmark,
    ): WorkspaceManifest =
        m.copy(bookmarks = m.bookmarks.map { if (it.id == id) transform(it) else it })
}
