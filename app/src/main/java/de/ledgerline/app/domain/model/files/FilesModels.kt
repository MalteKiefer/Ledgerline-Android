package de.ledgerline.app.domain.model.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Plaintext-relational Files models (server pivot v1.5xx — the zero-knowledge sealed sharded-store
 * model was removed). Every file/folder is an owner-scoped row with an integer [id], an optimistic
 * `version` (PUT → 409 `{error:version_conflict, version}`), and soft-delete (`deleted_at`). Bytes are
 * stored server-side and streamed as plaintext over TLS — there is NO client crypto, no `encFileKey`,
 * no manifest. Field names mirror the API (snake_case) via [SerialName]; decoding is lenient
 * (`ignoreUnknownKeys`), so additive server fields never break us.
 */

@Serializable
data class FileEntry(
    val id: Int = 0,
    @SerialName("file_folder_id") val folderId: Int? = null,
    val name: String = "",
    val mime: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    val tags: List<String> = emptyList(),
    val note: String? = null,
    val favorite: Boolean = false,
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    /** Present only when the server eager-loads labels (data/search/trash/setLabels). */
    val labels: List<FileLabel> = emptyList(),
)

/** Flat folder row; the tree is reconstructed client-side from [parentId] (null = root). */
@Serializable
data class FileFolder(
    val id: Int = 0,
    @SerialName("parent_id") val parentId: Int? = null,
    val name: String = "",
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class FileVersion(
    val id: Int = 0,
    @SerialName("file_id") val fileId: Int = 0,
    val size: Long = 0,
    val mime: String? = null,
    val sha256: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class FileLabel(
    val id: Int = 0,
    val name: String = "",
    val color: String = "#6b7280",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Account storage usage; [quota] null = unlimited. */
@Serializable
data class FilesUsage(
    val used: Long = 0,
    val quota: Long? = null,
)

/** The full owner-scoped files listing (`GET /files/data`). */
@Serializable
data class FilesData(
    val folders: List<FileFolder> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val usage: FilesUsage = FilesUsage(),
    val labels: List<FileLabel> = emptyList(),
)

/** `GET /files/trash`. */
@Serializable
data class FilesTrash(
    val files: List<FileEntry> = emptyList(),
    val folders: List<FileFolder> = emptyList(),
)

// ---- Activity feed (`/files/activity`, `/files/entries/{id}/activity`) ----

/**
 * One Files activity-feed entry (owner-scoped, newest first). [actor] is the display name of whoever
 * performed it, or the upload-link label for anonymous external uploads. The server also returns a
 * free-form `meta` object which we let `ignoreUnknownKeys` drop.
 */
@Serializable
data class FileActivity(
    val id: Int = 0,
    val action: String = "", // upload | external_upload | rename | move | version | trash | restore | delete | share
    @SerialName("file_id") val fileId: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_folder_id") val folderId: Int? = null,
    val actor: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ---- Info panel (`GET /files/entries/{id}/info`) ----

/** Extracted per-filetype metadata (image EXIF, PDF info, audio/video, STL geometry, text/archive). */
@Serializable
data class FileInfoMetadata(
    val kind: String = "",
    val fields: Map<String, String> = emptyMap(),
)

/** Sharing status inside the info panel (null = not shared). */
@Serializable
data class FileInfoShare(
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("allow_download") val allowDownload: Boolean = true,
    val protected: Boolean = false,
)

/** A same-checksum duplicate surfaced by the info panel. */
@Serializable
data class FileInfoDuplicate(
    val id: Int = 0,
    val name: String = "",
    val path: String = "",
)

/**
 * Rich info aggregate for one file (`GET /files/entries/{id}/info`): checksum, dates, version count,
 * folder [path], extracted [metadata], a content [snippet], [share] status, same-checksum
 * [duplicates] and recent [activity].
 */
@Serializable
data class FileInfo(
    val sha256: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val version: Int = 0,
    val versions: Int = 0,
    val path: String = "",
    val metadata: FileInfoMetadata? = null,
    val snippet: String? = null,
    val share: FileInfoShare? = null,
    val duplicates: List<FileInfoDuplicate> = emptyList(),
    val activity: List<FileActivity> = emptyList(),
)

// ---- Stats ----

@Serializable
data class DuplicateFile(
    val id: Int = 0,
    val name: String = "",
    val size: Long = 0,
    val path: String = "",
)

/** `GET /files/stats` — usage broken down by [FileType] enum key + sha256 duplicate groups. */
@Serializable
data class FilesStats(
    val used: Long = 0,
    @SerialName("by_type") val byType: Map<String, Long> = emptyMap(),
    val duplicates: List<List<DuplicateFile>> = emptyList(),
)

// ---- Sharing: public links (owner side, `/files/rel-shares`) ----

/** Owner-visible public-link share. Public URL = `{baseUrl}/file-share/{token}`. */
@Serializable
data class ShareView(
    val id: Int = 0,
    val token: String = "",
    val kind: String = "file", // file | folder
    @SerialName("file_id") val fileId: Int? = null,
    @SerialName("file_folder_id") val folderId: Int? = null,
    @SerialName("needs_password") val needsPassword: Boolean = false,
    @SerialName("allow_download") val allowDownload: Boolean = true,
    @SerialName("expires_at") val expiresAt: String? = null,
    val version: Int = 0,
    /** Present only in the owner-side index (`GET /files/rel-shares`): the shared file/folder name. */
    val name: String? = null,
)

// ---- Sharing: inbound upload links (owner side, `/files/upload-links`) ----

/**
 * Owner-visible public inbound upload link (`file_upload_links`): external people upload INTO the
 * owner's folder (write-only, owner quota). The token is the capability; public page = `{base}/u/{token}`.
 */
@Serializable
data class FileUploadLink(
    val id: Int = 0,
    val token: String = "",
    val label: String? = null,
    @SerialName("file_folder_id") val folderId: Int? = null,
    @SerialName("folder_name") val folderName: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

// ---- Sharing: cross-user folder shares ----

@Serializable
data class ShareMember(
    val id: Int = 0,
    @SerialName("user_id") val userId: Int = 0,
    val name: String? = null,
    val email: String? = null,
    val role: String = "viewer", // viewer | editor
)

@Serializable
data class FolderShareView(
    val id: Int = 0,
    // Cross-user shares are now folder-subtree OR single-file (server d55caef3). `kind` selects
    // which; file shares carry `fileId` and leave `folderId` at 0.
    val kind: String = "folder", // file | folder
    @SerialName("file_folder_id") val folderId: Int = 0,
    @SerialName("file_id") val fileId: Int? = null,
    @SerialName("folder_name") val folderName: String? = null,
    val members: List<ShareMember> = emptyList(),
)

// ---- Sharing: shared-with-me (member side) ----

@Serializable
data class ShareOwner(
    val id: Int = 0,
    val name: String = "",
    val email: String = "",
)

@Serializable
data class SharedWithMe(
    val id: Int = 0,
    @SerialName("folder_name") val folderName: String = "",
    val role: String = "viewer", // owner | editor | viewer
    val owner: ShareOwner = ShareOwner(),
)

/** Lightweight file row inside a shared-folder browse / public manifest (subset of [FileEntry]). */
@Serializable
data class SharedFile(
    val id: Int = 0,
    val name: String = "",
    val mime: String? = null,
    val size: Long = 0,
    @SerialName("file_folder_id") val folderId: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SharedFolder(
    val id: Int = 0,
    val name: String = "",
    @SerialName("parent_id") val parentId: Int? = null,
)

@Serializable
data class SharedBrowse(
    @SerialName("share_id") val shareId: Int = 0,
    val role: String = "viewer",
    val kind: String = "folder", // file | folder
    @SerialName("root_id") val rootId: Int = 0,
    val folders: List<SharedFolder> = emptyList(),
    val files: List<SharedFile> = emptyList(),
    // A lone shared file (kind=file) arrives under `file`, not in `files[]`.
    val file: SharedFile? = null,
)
