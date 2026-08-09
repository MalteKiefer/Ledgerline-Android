package de.ledgerline.app.ui.money

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.AppLockState
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.ThemeMode
import de.ledgerline.app.data.remote.dto.DeviceDto
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.data.remote.dto.NotificationDto
import de.ledgerline.app.data.remote.dto.UserSettingsDto
import de.ledgerline.app.domain.usecase.ForceLogout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Account + settings state: profile, connected devices, notifications, theme, and logout. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val account: AccountRepository,
    private val settings: SettingsStore,
    private val appLockState: AppLockState,
    private val forceLogout: ForceLogout,
    private val sessionHolder: de.ledgerline.app.core.SessionHolder,
) : ViewModel() {

    /** The connected server's base URL (for the About page). */
    fun serverUrl(): String? = sessionHolder.get()?.baseUrl
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

    private val _fileMaxVersions = MutableStateFlow(10)
    val fileMaxVersions: StateFlow<Int> = _fileMaxVersions.asStateFlow()

    private val _avatar = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val avatar: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _avatar.asStateFlow()

    init {
        viewModelScope.launch {
            val u = account.me()
            _me.value = u
            if (u?.hasAvatar == true) {
                val bytes = account.avatar()
                _avatar.value = bytes?.let {
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
                }
            }
        }
        viewModelScope.launch { account.getSettings()?.fileMaxVersions?.let { _fileMaxVersions.value = it } }
    }

    // ---- Language (per-app locale, Android 13+ LocaleManager) ----
    /** The current per-app language tag ("" = follow system). */
    fun currentLanguageTag(): String =
        context.getSystemService(LocaleManager::class.java)?.applicationLocales
            ?.takeIf { !it.isEmpty }?.get(0)?.language ?: ""

    fun setLanguage(tag: String) {
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales = if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        if (tag.isNotBlank()) viewModelScope.launch { account.pushLocale(tag) }
    }

    // ---- Files setting: kept versions per file ----
    fun setFileMaxVersions(n: Int) = viewModelScope.launch {
        val echoed = account.putSettings(UserSettingsDto(fileMaxVersions = n))
        _fileMaxVersions.value = echoed?.fileMaxVersions ?: n
    }

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
    suspend fun regenerateRecoveryCodes() = account.regenerateRecoveryCodes()

    // ---- password / account ----
    fun changePassword(current: String, new: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(account.changePassword(current, new)) }

    suspend fun exportAccount(): ByteArray? = account.exportAccount()

    fun deleteAccount(email: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = account.deleteAccount(email)
        if (ok) { forceLogout.invoke(); done(true) } else done(false)
    }

    // ---- WebDAV / sessions / paperless ----
    suspend fun webdav() = account.webdav()
    fun setWebdav(pw: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(account.setWebdav(pw) != null) }
    fun clearWebdav(done: (Boolean) -> Unit) = viewModelScope.launch { done(account.clearWebdav() != null) }
    suspend fun sessions() = account.sessions()
    fun revokeSession(id: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(account.revokeSession(id)) }

    suspend fun paperlessConfig() = account.paperlessConfig()
    fun savePaperless(enabled: Boolean, url: String, token: String?, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(account.updatePaperlessConfig(enabled, url, token) != null) }
    fun testPaperless(done: (Boolean) -> Unit) = viewModelScope.launch { done(account.testPaperless()) }
    fun paperlessSync(done: (Boolean) -> Unit) = viewModelScope.launch { done(account.paperlessSync()) }
}
