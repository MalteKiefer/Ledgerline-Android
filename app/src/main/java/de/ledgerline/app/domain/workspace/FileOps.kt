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

    // ---- Move (file / folder) ----

    /** Move a file into [targetFolderId] (null = root). Unknown id = no-op. */
    fun moveFile(m: WorkspaceManifest, fileId: String, targetFolderId: String?): WorkspaceManifest =
        update(m, fileId) { it.copy(folder = targetFolderId) }

    /**
     * Move a folder under [targetParentId] (null = root). Rejects moving a folder into
     * itself or one of its own descendants (would orphan/cycle the subtree) — returns the
     * manifest unchanged in that case. Unknown [folderId] = no-op.
     */
    fun moveFolder(m: WorkspaceManifest, folderId: String, targetParentId: String?): WorkspaceManifest {
        if (m.fileFolders.none { it.id == folderId }) return m
        if (targetParentId == folderId) return m
        if (targetParentId != null && isDescendant(m, folderId, targetParentId)) return m
        return m.copy(
            fileFolders = m.fileFolders.map {
                if (it.id == folderId) it.copy(parent = targetParentId) else it
            },
        )
    }

    /**
     * True when [candidateParentId] is [folderId] itself or lies somewhere in the subtree
     * rooted at [folderId] (walks up the parent chain from the candidate).
     */
    fun isDescendant(m: WorkspaceManifest, folderId: String, candidateParentId: String): Boolean {
        val byId = m.fileFolders.associateBy { it.id }
        var current: String? = candidateParentId
        // Guard against pre-existing cycles in bad data.
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current == folderId) return true
            current = byId[current]?.parent
        }
        return false
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

    /** The rebuilt entry after a version restore plus the blob refs the caller must free. */
    data class RestoreResult(val entry: FileEntry, val freedBlobs: List<String>)

    /**
     * Restore the version [versionId] of [entry] as the current content (mirrors the web
     * `restoreVersion`): snapshot the CURRENT blob as the newest [FileVersion] (prepended,
     * capped to [keep]), swap the entry's `blob/encFileKey/size/mime/name` to the chosen
     * version's, and drop that version from the list (it becomes current). Returns the
     * rebuilt entry and the overflow-blob refs freed by the cap.
     *
     * If [versionId] isn't found the entry is returned unchanged with no freed blobs.
     * Pure — mutates nothing.
     */
    fun restoreVersion(
        entry: FileEntry,
        versionId: String,
        nowIso: String,
        keep: Int = MAX_VERSIONS,
    ): RestoreResult {
        val target = entry.versions.firstOrNull { it.id == versionId }
            ?: return RestoreResult(entry, emptyList())

        // Snapshot the current content as a new version at the top.
        val snapshot = FileVersion(
            id = "restore-$versionId-$nowIso", blob = entry.blob, encFileKey = entry.encFileKey,
            size = entry.size, mime = entry.mime, name = entry.name, created = nowIso,
        )
        // Remaining versions = all but the one being restored; snapshot goes in front, then cap.
        val remaining = entry.versions.filterNot { it.id == versionId }
        val update = prependVersion(remaining, snapshot, keep)

        val restored = entry.copy(
            blob = target.blob,
            encFileKey = target.encFileKey,
            size = target.size,
            mime = target.mime,
            name = if (target.name.isNotBlank()) target.name else entry.name,
            versions = update.versions,
        )
        return RestoreResult(restored, update.freedBlobs)
    }
}
