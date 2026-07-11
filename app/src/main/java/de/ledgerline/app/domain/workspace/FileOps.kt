package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.FileVersion
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

    /** Max saved versions kept per file; older ones are evicted and their blobs freed. */
    const val MAX_VERSIONS = 20

    /** The newly capped version list plus the blob refs of the evicted overflow. */
    data class VersionUpdate(val versions: List<FileVersion>, val freedBlobs: List<String>)

    /**
     * In-app-editor save: snapshot the outgoing [previous] blob as the newest version
     * (prepended, mirroring the web `unshift`), then cap to [keep]. Returns the capped
     * list and the overflow blob refs the caller must free. Pure — mutates nothing.
     */
    fun prependVersion(
        existing: List<FileVersion>,
        previous: FileVersion,
        keep: Int = MAX_VERSIONS,
    ): VersionUpdate {
        val all = listOf(previous) + existing
        if (all.size <= keep) return VersionUpdate(all, emptyList())
        val kept = all.take(keep)
        val evicted = all.drop(keep)
        return VersionUpdate(kept, evicted.map { it.blob })
    }
}
