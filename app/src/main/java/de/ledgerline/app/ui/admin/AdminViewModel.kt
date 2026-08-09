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
    suspend fun notifications(): JsonObject? = repo.notifications()
    fun updateNotifications(body: JsonObject, done: (JsonObject?) -> Unit) = viewModelScope.launch { done(repo.updateNotifications(body)) }
    fun testNotification(channel: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.testNotification(channel)) }

    // ---- System ----
    suspend fun system(): JsonObject? = repo.system()
    fun resolveError(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.resolveError(id)) }
}
