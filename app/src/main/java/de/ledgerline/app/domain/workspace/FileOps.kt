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

    /** Move a file into [folderId] (null = root). Unknown id = no-op. */
    fun moveFile(m: WorkspaceManifest, id: String, folderId: String?): WorkspaceManifest =
        update(m, id) { it.copy(folder = folderId) }

    /** Toggle a file's favorite flag. Unknown id = no-op. */
    fun toggleFavorite(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(favorite = !it.favorite) }

    /** Replace a file's tag list. Unknown id = no-op. */
    fun setTags(m: WorkspaceManifest, id: String, tags: List<String>): WorkspaceManifest =
        update(m, id) { it.copy(tags = tags) }

    /**
     * Restore a saved [version] as the file's current content: snapshot the outgoing current blob
     * as a new version (so the restore is itself undoable), then point the file at the version's
     * blob/key/size/mime. Unknown id = no-op.
     */
    fun restoreVersion(m: WorkspaceManifest, id: String, version: FileVersion, nowIso: String): WorkspaceManifest =
        update(m, id) { f ->
            val snapshot = FileVersion(id = "", blob = f.blob, encFileKey = f.encFileKey, size = f.size, mime = f.mime, name = f.name, created = nowIso)
            val capped = prependVersion(f.versions.filterNot { it.blob == version.blob }, snapshot).versions
            f.copy(blob = version.blob, encFileKey = version.encFileKey, size = version.size, mime = version.mime.ifBlank { f.mime }, versions = capped)
        }

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
