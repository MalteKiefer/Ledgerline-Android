package de.ledgerline.app.ui.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.AppLockState
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.ThemeMode
import de.ledgerline.app.data.remote.dto.DeviceDto
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.data.remote.dto.NotificationDto
import de.ledgerline.app.domain.usecase.ForceLogout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Account + settings state: profile, connected devices, notifications, theme, and logout. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val account: AccountRepository,
    private val settings: SettingsStore,
    private val appLockState: AppLockState,
    private val forceLogout: ForceLogout,
) : ViewModel() {
    private val _me = MutableStateFlow<MeUser?>(null)
    val me: StateFlow<MeUser?> = _me.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceDto>>(emptyList())
    val devices: StateFlow<List<DeviceDto>> = _devices.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationDto>>(emptyList())
    val notifications: StateFlow<List<NotificationDto>> = _notifications.asStateFlow()

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.let { flow ->
            MutableStateFlow(ThemeMode.SYSTEM).also { s -> viewModelScope.launch { flow.collect { s.value = it } } }
        }

    init { viewModelScope.launch { _me.value = account.me() } }

    fun loadDevices() = viewModelScope.launch { _devices.value = account.devices() }
    fun revokeDevice(id: Long) = viewModelScope.launch { if (account.revokeDevice(id)) loadDevices() }
    fun wipeDevice(id: Long) = viewModelScope.launch { if (account.wipeDevice(id)) loadDevices() }

    fun loadNotifications() = viewModelScope.launch { _notifications.value = account.notifications()?.items.orEmpty() }
    fun markRead(id: Long) = viewModelScope.launch { if (account.markNotificationRead(id)) loadNotifications() }
    fun markAllRead() = viewModelScope.launch { if (account.markAllNotificationsRead()) loadNotifications() }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch {
        settings.setThemeMode(mode)
        account.pushTheme(mode.name.lowercase())
    }

    /** Server-revoke this token + wipe local state, then invoke [done] to leave the session. */
    fun logout(done: () -> Unit) = viewModelScope.launch {
        account.revokeCurrentSession()
        forceLogout.invoke()
        done()
    }

    fun lockNow() = appLockState.lock()

    // ---- 2FA ----
    suspend fun twoFactorBegin() = account.twoFactorBegin()
    fun twoFactorConfirm(code: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(account.twoFactorConfirm(code)) }
    fun twoFactorDisable(done: (Boolean) -> Unit) = viewModelScope.launch { done(account.twoFactorDisable()) }
    suspend fun recoveryCodes() = account.recoveryCodes()

    // ---- password / account ----
    fun changePassword(current: String, new: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(account.changePassword(current, new)) }

    suspend fun exportAccount(): ByteArray? = account.exportAccount()

    fun deleteAccount(email: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = account.deleteAccount(email)
        if (ok) { forceLogout.invoke(); done(true) } else done(false)
    }
}
