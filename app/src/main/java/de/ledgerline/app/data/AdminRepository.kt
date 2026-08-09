package de.ledgerline.app.data

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.AdminApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.admin.AdminGroup
import de.ledgerline.app.domain.model.admin.AdminUser
import de.ledgerline.app.domain.model.admin.DevicePolicy
import de.ledgerline.app.domain.model.admin.FilesLimits
import de.ledgerline.app.domain.model.admin.InviteLinkResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admin data layer (`can:manage-global-settings`). Online-only, no cache — admin screens fetch fresh.
 * Free-form JSON bodies; the UI builds exactly the fields to send (blank secrets preserve stored ones).
 */
@Singleton
class AdminRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
) {
    private fun api(): AdminApi {
        val s = sessionHolder.get() ?: error("no session")
        return NetworkFactory.createAdmin(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin)
    }

    private suspend fun <T> get(call: suspend (AdminApi) -> Response<T>): T? = withContext(Dispatchers.IO) {
        runCatching { call(api()).takeIf { it.isSuccessful }?.body() }.getOrNull()
    }
    private suspend fun ok(call: suspend (AdminApi) -> Response<*>): Boolean = withContext(Dispatchers.IO) {
        runCatching { call(api()).isSuccessful }.getOrDefault(false)
    }

    // ---- Users ----
    suspend fun users(): List<AdminUser> = get { it.users() }?.users.orEmpty()
    suspend fun createUser(body: JsonObject): AdminUser? = get { it.createUser(body) }?.user
    suspend fun updateUser(id: Int, body: JsonObject): AdminUser? = get { it.updateUser(id, body) }?.user
    suspend fun deleteUser(id: Int): Boolean = ok { it.deleteUser(id) }
    suspend fun resetUserPassword(id: Int): Boolean = ok { it.resetUserPassword(id) }
    suspend fun resetUserTwoFactor(id: Int): Boolean = ok { it.resetUserTwoFactor(id) }
    suspend fun inviteLink(id: Int, ttlHours: Int, send: Boolean): InviteLinkResponse? =
        get { it.inviteLink(id, buildJsonObject {
            put("ttl_hours", ttlHours)
            put("send", send)
        }) }

    // ---- Groups ----
    suspend fun groups(): List<AdminGroup> = get { it.groups() }?.groups.orEmpty()
    suspend fun createGroup(body: JsonObject): AdminGroup? = get { it.createGroup(body) }?.group
    suspend fun updateGroup(id: Int, body: JsonObject): AdminGroup? = get { it.updateGroup(id, body) }?.group
    suspend fun deleteGroup(id: Int): Boolean = ok { it.deleteGroup(id) }

    // ---- Workspace access ----
    suspend fun registration(): Boolean? = get { it.registration() }?.allowRegistration
    suspend fun setRegistration(allow: Boolean): Boolean? =
        get { it.updateRegistration(buildJsonObject { put("allow_registration", allow) }) }?.allowRegistration

    suspend fun devicePolicy(): DevicePolicy? = get { it.devicePolicy() }
    suspend fun setDevicePolicy(max: Int): DevicePolicy? =
        get { it.updateDevicePolicy(buildJsonObject { put("max_connected_devices", max) }) }

    suspend fun filesLimits(): FilesLimits? = get { it.filesLimits() }
    suspend fun setFilesLimits(maxUploadMb: Int, orphanGraceHours: Int): FilesLimits? =
        get { it.updateFilesLimits(buildJsonObject {
            put("files_max_upload_mb", maxUploadMb)
            put("files_blob_orphan_grace_hours", orphanGraceHours)
        }) }

    // ---- Notifications ----
    suspend fun notifications(): de.ledgerline.app.domain.model.admin.NotificationsSettings? = get { it.notifications() }
    suspend fun updateNotifications(body: JsonObject): de.ledgerline.app.domain.model.admin.NotificationsSettings? = get { it.updateNotifications(body) }
    suspend fun testNotification(channel: String): Boolean =
        ok { it.testNotification(buildJsonObject { put("channel", channel) }) }

    // ---- System ----
    suspend fun system(): de.ledgerline.app.domain.model.admin.SystemOverview? = get { it.system() }
    suspend fun resolveError(id: Int): Boolean = ok { it.resolveError(id) }

    // ---- Security log ----
    suspend fun securityLog(action: String?, user: Int?, since: String?, page: Int, perPage: Int) =
        get { it.securityLog(action?.ifBlank { null }, user, since?.ifBlank { null }, page, perPage) }
    suspend fun securityLogExport(format: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().securityLogExport(format, 10000).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }

    // ---- Backup ----
    suspend fun backupDestinations() = get { it.backupDestinations() }?.destinations.orEmpty()
    suspend fun createBackupDestination(body: JsonObject) = ok { it.createBackupDestination(body) }
    suspend fun updateBackupDestination(id: Int, body: JsonObject) = ok { it.updateBackupDestination(id, body) }
    suspend fun deleteBackupDestination(id: Int) = ok { it.deleteBackupDestination(id) }
    suspend fun testBackupDestination(body: JsonObject): Boolean =
        get { it.testBackupDestination(body) }?.ok == true

    suspend fun backupJobs() = get { it.backupJobs() }?.jobs.orEmpty()
    suspend fun createBackupJob(body: JsonObject) = get { it.createBackupJob(body) }?.job
    suspend fun updateBackupJob(id: Int, body: JsonObject) = get { it.updateBackupJob(id, body) }?.job
    suspend fun deleteBackupJob(id: Int) = ok { it.deleteBackupJob(id) }
    suspend fun runBackupJob(id: Int) = ok { it.runBackupJob(id) }

    suspend fun backupRuns() = get { it.backupRuns() }?.runs.orEmpty()
    suspend fun downloadBackupRun(id: Int, source: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().downloadBackupRun(id, source).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }
    suspend fun verifyBackupRun(id: Int, source: String, passphrase: String?) =
        get { it.verifyBackupRun(id, buildJsonObject { put("source", source); if (!passphrase.isNullOrBlank()) put("passphrase", passphrase) }) }
    suspend fun cancelBackupRun(id: Int) = ok { it.cancelBackupRun(id) }
    suspend fun decryptBackupRun(id: Int, source: String, passphrase: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            api().decryptBackupRun(id, buildJsonObject { put("source", source); put("passphrase", passphrase) })
                .takeIf { it.isSuccessful }?.body()?.bytes()
        }.getOrNull()
    }
    suspend fun restoreBackupRun(id: Int, source: String) =
        get { it.restoreBackupRun(id, buildJsonObject { put("source", source) }) }
}
