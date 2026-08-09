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

// ---- Notifications (SMTP / ntfy / webhook). Secrets read-only via has_* booleans. ----
@Serializable
data class NotificationsSettings(
    @SerialName("mail_enabled") val mailEnabled: Boolean = false,
    @SerialName("smtp_host") val smtpHost: String? = null,
    @SerialName("smtp_port") val smtpPort: Int? = null,
    @SerialName("smtp_encryption") val smtpEncryption: String? = null, // tls | ssl
    @SerialName("smtp_username") val smtpUsername: String? = null,
    @SerialName("smtp_from_address") val smtpFromAddress: String? = null,
    @SerialName("smtp_from_name") val smtpFromName: String? = null,
    @SerialName("has_smtp_password") val hasSmtpPassword: Boolean = false,
    @SerialName("ntfy_enabled") val ntfyEnabled: Boolean = false,
    @SerialName("ntfy_url") val ntfyUrl: String? = null,
    @SerialName("ntfy_topic") val ntfyTopic: String? = null,
    @SerialName("has_ntfy_token") val hasNtfyToken: Boolean = false,
    @SerialName("webhook_enabled") val webhookEnabled: Boolean = false,
    @SerialName("webhook_url") val webhookUrl: String? = null,
    @SerialName("has_webhook_secret") val hasWebhookSecret: Boolean = false,
)

// ---- System status (read-only ops dashboard) ----
@Serializable data class SysQueue(val pending: Int = 0, val failed: Int = 0)
@Serializable data class SysStorage(val files: Long = 0, val gallery: Long = 0, val database: Long = 0, val total: Long = 0)
@Serializable data class SysErrorsSummary(val unresolved: Int = 0, val total: Int = 0, val lastAt: String? = null)
@Serializable data class SysBackup(val lastSuccessAt: String? = null, val lastVerifyStatus: String? = null, val lastVerifyAt: String? = null)
@Serializable data class SysDisk(val free: Long = 0, val total: Long = 0)
@Serializable
data class SystemStatus(
    val version: String = "",
    val queue: SysQueue = SysQueue(),
    val storage: SysStorage = SysStorage(),
    val errors: SysErrorsSummary = SysErrorsSummary(),
    val backup: SysBackup = SysBackup(),
    val disk: SysDisk = SysDisk(),
)
@Serializable
data class SysErrorEvent(
    val id: Int = 0,
    val level: String = "",
    val exception: String = "",
    val message: String = "",
    val file: String = "",
    val line: Int = 0,
    val count: Int = 0,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)
@Serializable
data class SystemOverview(
    val status: SystemStatus = SystemStatus(),
    val errors: List<SysErrorEvent> = emptyList(),
)

// ---- Security log ----
@Serializable
data class AuditEntry(
    val at: String? = null,
    @SerialName("user_id") val userId: Int? = null,
    val actor: String? = null,
    val action: String = "",
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
)
@Serializable data class AuditMeta(val total: Int = 0, @SerialName("per_page") val perPage: Int = 50, @SerialName("current_page") val currentPage: Int = 1, @SerialName("last_page") val lastPage: Int = 1)
@Serializable data class SecurityLogPage(val data: List<AuditEntry> = emptyList(), val meta: AuditMeta = AuditMeta())

// ---- Backup ----
@Serializable data class BackupDestination(val id: Int = 0, val name: String = "", val driver: String = "")
@Serializable data class BackupDestinationsResponse(val destinations: List<BackupDestination> = emptyList())
@Serializable
data class BackupJobStats(
    val runs: Int = 0, val ok: Int = 0, val failed: Int = 0, val successRate: Double = 0.0,
    val lastStatus: String? = null, val lastRun: String? = null, val nextRun: String? = null,
    val totalBytes: Long = 0, val lastBytes: Long? = null,
)
@Serializable
data class BackupJob(
    val id: Int = 0, val name: String = "",
    val source: String = "", val sources: List<String> = emptyList(), val mode: String = "full",
    @SerialName("destination_id") val destinationId: Int = 0, val cron: String = "",
    val retention: Int = 1, @SerialName("keep_daily") val keepDaily: Int = 0,
    @SerialName("keep_weekly") val keepWeekly: Int = 0, @SerialName("keep_monthly") val keepMonthly: Int = 0,
    val encrypt: Boolean = false, @SerialName("notify_channels") val notifyChannels: List<String> = emptyList(),
    val enabled: Boolean = true,
    @SerialName("last_run_at") val lastRunAt: String? = null, @SerialName("last_status") val lastStatus: String? = null,
    val statistics: BackupJobStats = BackupJobStats(),
)
@Serializable data class BackupJobsResponse(val jobs: List<BackupJob> = emptyList())
@Serializable data class BackupJobResponse(val job: BackupJob)
@Serializable data class BackupArchive(val source: String = "", val encrypted: Boolean = false, val restorable: Boolean = false)
@Serializable
data class BackupRun(
    val id: Int = 0, val job: String? = null, val status: String = "", val message: String? = null,
    val startedIso: String? = null, val startedHuman: String? = null, val size: String? = null,
    val archives: List<BackupArchive> = emptyList(),
    val cancellable: Boolean = false, val cancelling: Boolean = false,
    val verifyStatus: String? = null, val verifyMessage: String? = null,
)
@Serializable data class BackupRunsResponse(val runs: List<BackupRun> = emptyList())

val BACKUP_DRIVERS = listOf("s3", "b2", "sftp", "webdav")
val BACKUP_SOURCES = listOf("database", "invoices", "files")
val BACKUP_MODES = listOf("full", "incremental")
val BACKUP_NOTIFY_CHANNELS = listOf("desktop", "mail", "ntfy", "webhook")

/** The available application modules (users/groups module allow-lists). */
val ADMIN_MODULES = listOf("finance", "files", "contacts", "calendar")
