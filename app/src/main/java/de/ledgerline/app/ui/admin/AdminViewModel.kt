package de.ledgerline.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.AdminRepository
import de.ledgerline.app.domain.model.admin.AdminUser
import de.ledgerline.app.domain.model.admin.DevicePolicy
import de.ledgerline.app.domain.model.admin.FilesLimits
import de.ledgerline.app.domain.model.admin.InviteLinkResponse
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** State + actions for the admin section (loaded on demand; online-only). */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val repo: AdminRepository,
) : ViewModel() {

    // ---- Users ----
    suspend fun users(): List<AdminUser> = repo.users()
    fun saveUser(id: Int?, body: JsonObject, done: (AdminUser?) -> Unit) =
        viewModelScope.launch { done(if (id == null) repo.createUser(body) else repo.updateUser(id, body)) }
    fun deleteUser(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteUser(id)) }
    fun resetPassword(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.resetUserPassword(id)) }
    fun resetTwoFactor(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.resetUserTwoFactor(id)) }
    fun inviteLink(id: Int, ttlHours: Int, send: Boolean, done: (InviteLinkResponse?) -> Unit) =
        viewModelScope.launch { done(repo.inviteLink(id, ttlHours, send)) }

    // ---- Workspace access ----
    suspend fun registration(): Boolean? = repo.registration()
    fun setRegistration(allow: Boolean, done: (Boolean?) -> Unit) = viewModelScope.launch { done(repo.setRegistration(allow)) }
    suspend fun devicePolicy(): DevicePolicy? = repo.devicePolicy()
    fun setDevicePolicy(max: Int, done: (DevicePolicy?) -> Unit) = viewModelScope.launch { done(repo.setDevicePolicy(max)) }
    suspend fun filesLimits(): FilesLimits? = repo.filesLimits()
    fun setFilesLimits(maxMb: Int, graceHours: Int, done: (FilesLimits?) -> Unit) =
        viewModelScope.launch { done(repo.setFilesLimits(maxMb, graceHours)) }

    // ---- Notifications ----
    suspend fun notifications() = repo.notifications()
    fun updateNotifications(body: JsonObject, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.updateNotifications(body) != null) }
    fun testNotification(channel: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.testNotification(channel)) }

    // ---- System ----
    suspend fun system() = repo.system()
    fun resolveError(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.resolveError(id)) }

    // ---- Groups ----
    suspend fun groups() = repo.groups()
    fun saveGroup(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        viewModelScope.launch { done((if (id == null) repo.createGroup(body) else repo.updateGroup(id, body)) != null) }
    fun deleteGroup(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteGroup(id)) }

    // ---- Security log ----
    suspend fun securityLog(action: String?, user: Int?, since: String?, page: Int, perPage: Int) =
        repo.securityLog(action, user, since, page, perPage)
    suspend fun securityLogExport(format: String) = repo.securityLogExport(format)

    // ---- Backup ----
    suspend fun backupDestinations() = repo.backupDestinations()
    suspend fun backupJobs() = repo.backupJobs()
    suspend fun backupRuns() = repo.backupRuns()
    fun runBackupJob(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.runBackupJob(id)) }
    fun deleteBackupJob(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteBackupJob(id)) }
    fun saveBackupJob(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        viewModelScope.launch { done((if (id == null) repo.createBackupJob(body) else repo.updateBackupJob(id, body)) != null) }
    fun cancelBackupRun(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.cancelBackupRun(id)) }
    fun verifyBackupRun(id: Int, source: String, passphrase: String?, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.verifyBackupRun(id, source, passphrase)?.ok == true) }
    fun deleteBackupDestination(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteBackupDestination(id)) }
    fun saveBackupDestination(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(if (id == null) repo.createBackupDestination(body) else repo.updateBackupDestination(id, body)) }
    fun testBackupDestination(body: JsonObject, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.testBackupDestination(body)) }
    suspend fun downloadBackupRun(id: Int, source: String) = repo.downloadBackupRun(id, source)
    suspend fun restoreBackupRun(id: Int, source: String) = repo.restoreBackupRun(id, source)?.ok == true
}
