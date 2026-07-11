package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.WorkspaceManifest

/**
 * Pure manifest transforms for note management. Each returns a new
 * [WorkspaceManifest]; mutating an unknown id is a safe no-op. Mirrors the web
 * `vaultWorkspace` note ops (see [TodoOps] for the same style).
 */
object NoteOps {

    /** Append a fresh, empty note (the editor fills title/content afterwards). */
    fun addNote(m: WorkspaceManifest, id: String, nowIso: String): WorkspaceManifest {
        val note = Note(
            id = id,
            title = "",
            content = "",
            pinned = false,
            trashed = false,
            updated = nowIso,
        )
        return m.copy(notes = m.notes + note)
    }

    fun updateNote(
        m: WorkspaceManifest,
        id: String,
        title: String,
        content: String,
        nowIso: String,
    ): WorkspaceManifest = update(m, id) {
        it.copy(title = title.trim(), content = content, updated = nowIso)
    }

    /**
     * Save a note by id: update it if it exists, otherwise append it. Lets the editor
     * open on a locally-created (not-yet-persisted) note and persist on first save —
     * so creation doesn't depend on a network round-trip populating the cache first.
     */
    fun upsertNote(
        m: WorkspaceManifest,
        id: String,
        title: String,
        content: String,
        nowIso: String,
    ): WorkspaceManifest =
        if (m.notes.any { it.id == id }) {
            updateNote(m, id, title, content, nowIso)
        } else {
            m.copy(
                notes = m.notes + Note(
                    id = id, title = title.trim(), content = content,
                    pinned = false, trashed = false, updated = nowIso,
                ),
            )
        }

    fun togglePin(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(pinned = !it.pinned) }

    fun trashNote(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(trashed = true) }

    private inline fun update(
        m: WorkspaceManifest,
        id: String,
        transform: (Note) -> Note,
    ): WorkspaceManifest =
        m.copy(notes = m.notes.map { if (it.id == id) transform(it) else it })
}
