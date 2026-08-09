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
    @SerialName("file_folder_id") val folderId: Int = 0,
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
    @SerialName("root_id") val rootId: Int = 0,
    val folders: List<SharedFolder> = emptyList(),
    val files: List<SharedFile> = emptyList(),
)
