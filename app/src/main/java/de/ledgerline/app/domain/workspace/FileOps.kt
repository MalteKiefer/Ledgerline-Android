package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.WorkspaceManifest

/**
 * Pure manifest transforms for file trash management. Each returns a new
 * [WorkspaceManifest]; mutating an unknown id is a safe no-op. Mirrors [NoteOps] and
 * the web `vaultFiles` soft-trash flow (trash → restore / delete-forever / empty).
 *
 * These only touch the manifest; freeing the underlying content blobs (and per-file
 * version blobs) on permanent delete is the caller's job.
 */
object FileOps {

    /** Soft-delete: move a file to the trash. Unknown id = no-op. */
    fun trashFile(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(trashed = true) }

    /** Move a trashed file back to the active list. Unknown id = no-op. */
    fun restoreFile(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(trashed = false) }

    /** Delete a file forever: remove it from the manifest entirely. Unknown id = no-op. */
    fun removeFile(m: WorkspaceManifest, id: String): WorkspaceManifest =
        m.copy(files = m.files.filterNot { it.id == id })

    /** Empty the trash: drop every trashed file (keeps the live ones). */
    fun emptyTrashFiles(m: WorkspaceManifest): WorkspaceManifest =
        m.copy(files = m.files.filterNot { it.trashed })

    private inline fun update(
        m: WorkspaceManifest,
        id: String,
        transform: (FileEntry) -> FileEntry,
    ): WorkspaceManifest =
        m.copy(files = m.files.map { if (it.id == id) transform(it) else it })
}
