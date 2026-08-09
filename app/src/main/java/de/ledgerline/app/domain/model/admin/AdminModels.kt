package de.ledgerline.app.domain.model.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Admin-area models (gated by `can:manage-global-settings`; the client shows the section only when
 * `/me.user.groups` contains "admin"). Secrets are write-only server-side — reads expose only `has_*`
 * booleans, and a blank secret on write preserves the stored value. Lenient decode (`ignoreUnknownKeys`).
 */

/** A group reference on a user row. */
@Serializable
data class AdminUserGroup(val id: Int = 0, val name: String = "")

@Serializable
data class AdminUser(
    val id: Int = 0,
    val name: String = "",
    val email: String = "",
    val role: String = "user", // admin | user
    @SerialName("max_connected_devices") val maxConnectedDevices: Int? = null,
    val modules: List<String>? = null, // null = all modules
    val groups: List<AdminUserGroup> = emptyList(),
    val verified: Boolean = false,
    @SerialName("two_factor") val twoFactor: Boolean = false,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
)

@Serializable data class AdminUsersResponse(val users: List<AdminUser> = emptyList())
@Serializable data class AdminUserResponse(val user: AdminUser)

/** A group member reference. */
@Serializable
data class AdminGroupMember(val id: Int = 0, val name: String = "", val email: String = "")

@Serializable
data class AdminGroup(
    val id: Int = 0,
    val name: String = "",
    @SerialName("max_connected_devices") val maxConnectedDevices: Int? = null,
    val shareable: Boolean = false,
    val modules: List<String>? = null,
    val members: List<AdminGroupMember> = emptyList(),
)

@Serializable data class AdminGroupsResponse(val groups: List<AdminGroup> = emptyList())
@Serializable data class AdminGroupResponse(val group: AdminGroup)

@Serializable data class RegistrationSetting(@SerialName("allow_registration") val allowRegistration: Boolean = false)
@Serializable data class DevicePolicy(@SerialName("max_connected_devices") val maxConnectedDevices: Int = 3)
@Serializable data class FilesLimits(
    @SerialName("files_max_upload_mb") val filesMaxUploadMb: Int = 512,
    @SerialName("files_blob_orphan_grace_hours") val filesBlobOrphanGraceHours: Int = 24,
)

@Serializable
data class InviteLinkResponse(
    val url: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    val sent: Boolean = false,
)

@Serializable data class AdminOk(val ok: Boolean = false, val detail: String? = null)
@Serializable data class AdminMessage(val message: String = "")

/** The available application modules (users/groups module allow-lists). */
val ADMIN_MODULES = listOf("finance", "files", "contacts", "calendar")
