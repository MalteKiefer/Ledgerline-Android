package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.TodoItem

/**
 * Pure, case-insensitive substring match predicates for the workspace text search,
 * mirroring the web client's live-filter behaviour (title/content/tags/url/name).
 *
 * A blank query matches everything; matching is done on the lowercased query against
 * the lowercased searchable fields (tags matched per-element).
 */
object WorkspaceSearch {

    /** Notes match on title, content, or any tag. */
    fun matches(note: Note, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        val needle = q.lowercase()
        return note.title.contains(needle, ignoreCase = true) ||
            note.content.contains(needle, ignoreCase = true) ||
            note.tags.anyContains(needle)
    }

    /** Bookmarks match on title, url, description, or any tag. */
    fun matches(bookmark: Bookmark, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        val needle = q.lowercase()
        return bookmark.title.contains(needle, ignoreCase = true) ||
            bookmark.url.contains(needle, ignoreCase = true) ||
            bookmark.description.contains(needle, ignoreCase = true) ||
            bookmark.tags.anyContains(needle)
    }

    /** Todos match on title, description, or any tag. */
    fun matches(todo: TodoItem, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        val needle = q.lowercase()
        return todo.title.contains(needle, ignoreCase = true) ||
            todo.description.contains(needle, ignoreCase = true) ||
            todo.tags.anyContains(needle)
    }

    /** Files match on name only. */
    fun matches(file: FileEntry, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        return file.name.contains(q, ignoreCase = true)
    }

    /** Folders match on name only (keeps a folder visible when its name matches). */
    fun matches(folder: NamedFolder, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        return folder.name.contains(q, ignoreCase = true)
    }

    private fun List<String>.anyContains(lowerNeedle: String): Boolean =
        any { it.contains(lowerNeedle, ignoreCase = true) }
}
