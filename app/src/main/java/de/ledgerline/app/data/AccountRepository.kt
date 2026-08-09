package de.ledgerline.app.data

import de.ledgerline.app.core.AuthEventBus
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.domain.model.Session
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches the signed-in account identity from `GET /api/v1/me`. */
@Singleton
class AccountRepository(
    private val sessionHolder: SessionHolder,
    private val authEventBus: AuthEventBus,
    private val prefsSink: de.ledgerline.app.core.prefs.DisplayPrefsSink,
    private val moduleAccess: de.ledgerline.app.core.ModuleAccess,
    private val snapshotCache: de.ledgerline.app.core.AccountSnapshotCache,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder, authEventBus: AuthEventBus, prefsSink: de.ledgerline.app.core.prefs.DisplayPrefsSink, moduleAccess: de.ledgerline.app.core.ModuleAccess, snapshotCache: de.ledgerline.app.core.AccountSnapshotCache) : this(
        sessionHolder,
        authEventBus,
        prefsSink,
        moduleAccess,
        snapshotCache,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    /** Mirror the server's display preferences into local settings (source of truth = server). */
    private suspend fun adoptPrefs(dto: de.ledgerline.app.data.remote.dto.DisplayPrefsDto?) {
        dto ?: return
        prefsSink.setDisplayPrefs(
            de.ledgerline.app.core.prefs.DisplayPrefs(
                distance = dto.distance ?: "km",
                elevation = dto.elevation ?: "m",
                weight = dto.weight ?: "kg",
                temp = dto.temp ?: "c",
                glucose = dto.glucose ?: "mgdl",
                timeFormat = dto.timeFormat ?: "24h",
            ),
        )
    }

    /** Push updated display preferences to the server, then persist locally (optimistic). */
    suspend fun pushPreferences(prefs: de.ledgerline.app.core.prefs.DisplayPrefs): Boolean {
        prefsSink.setDisplayPrefs(prefs)
        val session = sessionHolder.get() ?: return false
        return try {
            apiProvider(session).putPreferences(
                de.ledgerline.app.data.remote.dto.DisplayPrefsDto(
                    distance = prefs.distance, elevation = prefs.elevation, weight = prefs.weight,
                    temp = prefs.temp, glucose = prefs.glucose, timeFormat = prefs.timeFormat,
                ),
            ).isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Current account (name/email/groups). Null on no session, network error, or failure.
     * Also carries the remote kill switch: when the response's `wipe` flag is set, fire the
     * wipe event so the app erases all local state and re-pairs.
     */
    suspend fun me(): MeUser? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).me()
            if (!res.isSuccessful) return null
            val body = res.body()
            if (body?.wipe == true) authEventBus.emitWipe()
            adoptPrefs(body?.user?.preferences)
            moduleAccess.set(body?.user?.modules)
            body?.user
        } catch (_: Exception) {
            null
        }
    }

    /** Account name + account-wide storage: [usedBytes] = files+gallery, [quotaBytes] = combined limit (null = unlimited). */
    data class AccountSnapshot(val name: String?, val usedBytes: Long, val quotaBytes: Long?)

    /** Last cached snapshot for a cache-first first paint (offline-safe), or null if none cached. */
    fun cachedSnapshot(): AccountSnapshot? =
        snapshotCache.get()?.let { AccountSnapshot(it.name, it.usedBytes, it.quotaBytes) }

    /**
     * One `/me` fetch yielding both the display name and the **combined** (files + gallery) storage
     * figures the server started exposing as `usage.quota` (web `7b2ad183`). `quotaBytes` is null
     * when unlimited. Also honours the remote wipe flag. Null on no session / network error.
     */
    suspend fun snapshot(): AccountSnapshot? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).me()
            if (!res.isSuccessful) return null
            val body = res.body() ?: return null
            if (body.wipe) authEventBus.emitWipe()
            adoptPrefs(body.user.preferences)
            moduleAccess.set(body.user.modules)
            val u = body.usage
            AccountSnapshot(
                name = body.user.name,
                usedBytes = (u?.files ?: 0L) + (u?.gallery ?: 0L),
                quotaBytes = u?.quota,
            ).also { snapshotCache.put(de.ledgerline.app.core.AccountSnapshotCache.Snap(it.name, it.usedBytes, it.quotaBytes)) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Report device sync activity (`POST /device/heartbeat`) and honour the remote-wipe kill switch
     * the response carries (any heartbeat delivers it, like `/me`). Best-effort; safe to call often.
     */
    suspend fun heartbeat(state: String) {
        val session = sessionHolder.get() ?: return
        try {
            val res = apiProvider(session).deviceHeartbeat(de.ledgerline.app.data.remote.dto.HeartbeatRequest(state))
            if (res.isSuccessful && res.body()?.wipe == true) authEventBus.emitWipe()
        } catch (_: Exception) { /* best-effort */ }
    }

    /** Recent in-app notifications + unread count (`GET /notifications`). Null on no session/failure. */
    suspend fun notifications(): de.ledgerline.app.data.remote.dto.NotificationsResponse? {
        val session = sessionHolder.get() ?: return null
        return try {
            val r = apiProvider(session).notifications(null)
            if (r.isSuccessful) r.body() else null
        } catch (_: Exception) { null }
    }

    /** Mark one notification read (`POST /notifications/{id}/read`). */
    suspend fun markNotificationRead(id: Long): Boolean = call { it.markNotificationRead(id) }

    /** Mark all notifications read (`POST /notifications/read-all`). */
    suspend fun markAllNotificationsRead(): Boolean = call { it.markAllNotificationsRead() }

    /** Per-user non-display settings (`GET /settings`): contact notify channels + file version cap. */
    suspend fun getSettings(): de.ledgerline.app.data.remote.dto.UserSettingsDto? {
        val session = sessionHolder.get() ?: return null
        return try {
            val r = apiProvider(session).getSettings()
            if (r.isSuccessful) r.body() else null
        } catch (_: Exception) { null }
    }

    /** Partial-update per-user settings (`PUT /settings`); returns the server echo or null on failure. */
    suspend fun putSettings(dto: de.ledgerline.app.data.remote.dto.UserSettingsDto): de.ledgerline.app.data.remote.dto.UserSettingsDto? {
        val session = sessionHolder.get() ?: return null
        return try {
            val r = apiProvider(session).putSettings(dto)
            if (r.isSuccessful) r.body() else null
        } catch (_: Exception) { null }
    }

    /** GDPR export: stream the account zip (`GET /account/export`) as raw bytes. Null on failure. */
    suspend fun exportAccount(): ByteArray? {
        val session = sessionHolder.get() ?: return null
        return try {
            val r = apiProvider(session).accountExport()
            if (r.isSuccessful) r.body()?.bytes() else null
        } catch (_: Exception) { null }
    }

    /** Crypto-shred the account (`DELETE /account`, `confirmation` = email). Fires ForceLogout on success. */
    suspend fun deleteAccount(emailConfirmation: String): Boolean {
        val session = sessionHolder.get() ?: return false
        return try {
            val r = apiProvider(session).deleteAccount(de.ledgerline.app.data.remote.dto.DeleteAccountRequest(emailConfirmation))
            val ok = r.isSuccessful && r.body()?.deleted == true
            if (ok) authEventBus.emitWipe()   // erase all local state + re-pair (account is gone)
            ok
        } catch (_: Exception) { false }
    }

    /** Persist the chosen locale on the server profile (`POST /locale`). Best-effort. */
    suspend fun pushLocale(locale: String): Boolean = call { it.putLocale(de.ledgerline.app.data.remote.dto.LocaleRequest(locale)) }

    /** Persist the chosen theme (`POST /theme`; light|dark|system). Best-effort. */
    suspend fun pushTheme(theme: String): Boolean = call { it.putTheme(de.ledgerline.app.data.remote.dto.ThemeRequest(theme)) }

    // ── Login (account) 2FA — orthogonal to the ZK vault passphrase ──

    /**
     * Begin TOTP setup → the QR SVG + secret + otpauth URI (`enable` then `qr`). Since v1.562.0 the
     * enable step needs the login password (step-up). A 404 from `qr()` means 2FA is already confirmed
     * (the secret is never re-issued) — surfaced as null.
     */
    suspend fun twoFactorBegin(currentPassword: String): de.ledgerline.app.data.remote.dto.TwoFactorQrResponse? {
        val session = sessionHolder.get() ?: return null
        return try {
            val api = apiProvider(session)
            if (!api.twoFactorEnable(de.ledgerline.app.data.remote.dto.CurrentPasswordRequest(currentPassword)).isSuccessful) return null
            val r = api.twoFactorQr()
            if (r.isSuccessful) r.body() else null
        } catch (_: Exception) { null }
    }

    /** Confirm TOTP with a live code. */
    suspend fun twoFactorConfirm(code: String): Boolean =
        call { it.twoFactorConfirm(de.ledgerline.app.data.remote.dto.TwoFactorConfirmRequest(code)) }

    suspend fun twoFactorDisable(currentPassword: String): Boolean {
        val session = sessionHolder.get() ?: return false
        return try { apiProvider(session).twoFactorDisable(de.ledgerline.app.data.remote.dto.CurrentPasswordRequest(currentPassword)).isSuccessful } catch (_: Exception) { false }
    }

    suspend fun recoveryCodes(currentPassword: String): List<String> {
        val session = sessionHolder.get() ?: return emptyList()
        return try {
            val r = apiProvider(session).twoFactorRecoveryCodes(currentPassword)
            if (r.isSuccessful) r.body()?.recovery_codes.orEmpty() else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun regenerateRecoveryCodes(currentPassword: String): List<String> {
        val session = sessionHolder.get() ?: return emptyList()
        return try {
            val r = apiProvider(session).twoFactorRegenerateRecoveryCodes(de.ledgerline.app.data.remote.dto.CurrentPasswordRequest(currentPassword))
            if (r.isSuccessful) r.body()?.recovery_codes.orEmpty() else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** Change the app LOGIN password (not the vault passphrase). Returns true on success. */
    suspend fun changePassword(current: String, new: String): Boolean =
        call { it.changePassword(de.ledgerline.app.data.remote.dto.ChangePasswordRequest(current, new, new)) }

    /** The signed-in user's avatar image bytes (non-secret, `GET /avatar`), or null if none/failure. */
    suspend fun avatar(): ByteArray? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).avatar()
            if (res.isSuccessful) res.body()?.bytes() else null
        } catch (_: Exception) { null }
    }

    /** The owner's paired devices, current device first. Empty on no session/failure. */
    suspend fun devices(): List<de.ledgerline.app.data.remote.dto.DeviceDto> {
        val session = sessionHolder.get() ?: return emptyList()
        return try {
            val res = apiProvider(session).devices()
            if (res.isSuccessful) res.body()?.devices.orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Revoke a device's token (it can no longer sync). Returns true on success. */
    suspend fun revokeDevice(id: Long): Boolean = call { it.revokeDevice(id.toString()) }

    /** Flag a device to erase its local state on next contact (remote kill switch). */
    suspend fun wipeDevice(id: Long): Boolean = call { it.wipeDevice(id.toString()) }

    /** Revoke THIS device's token server-side (logout). Local wipe is the caller's job. */
    suspend fun revokeCurrentSession(): Boolean = call { it.deleteSession() }

    private suspend fun call(block: suspend (LedgerlineApi) -> retrofit2.Response<Unit>): Boolean {
        val session = sessionHolder.get() ?: return false
        return try {
            block(apiProvider(session)).isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    // ---- Browser sessions ----
    suspend fun sessions(): List<de.ledgerline.app.data.remote.dto.SessionRow> {
        val s = sessionHolder.get() ?: return emptyList()
        return try { apiProvider(s).accountSessions().takeIf { it.isSuccessful }?.body()?.sessions.orEmpty() } catch (_: Exception) { emptyList() }
    }
    suspend fun revokeSession(id: String): Boolean = call { it.revokeAccountSession(id) }

    // ---- App-specific WebDAV mount password ----
    suspend fun webdav(): de.ledgerline.app.data.remote.dto.WebDavStatus? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).webdav().takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
    suspend fun setWebdav(password: String): de.ledgerline.app.data.remote.dto.WebDavStatus? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).updateWebdav(de.ledgerline.app.data.remote.dto.WebDavRequest(password)).takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
    suspend fun clearWebdav(): de.ledgerline.app.data.remote.dto.WebDavStatus? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).clearWebdav().takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }

    // ---- Owner-side device pairing (approve a new device) ----
    suspend fun createDevicePairing(): de.ledgerline.app.data.remote.dto.DevicePairingCreated? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).createDevicePairing().takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
    suspend fun devicePairingStatus(id: Long): de.ledgerline.app.data.remote.dto.DevicePairingStatus? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).devicePairingStatus(id).takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
    suspend fun approveDevicePairing(id: Long): Boolean = call { it.approveDevicePairing(id) }
    suspend fun rejectDevicePairing(id: Long): Boolean = call { it.rejectDevicePairing(id) }

    // ---- Paperless-ngx integration ----
    suspend fun paperlessConfig(): de.ledgerline.app.data.remote.dto.PaperlessConfig? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).paperlessConfig().takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
    suspend fun updatePaperlessConfig(enabled: Boolean, url: String, token: String?): de.ledgerline.app.data.remote.dto.PaperlessConfig? {
        val s = sessionHolder.get() ?: return null
        val body = de.ledgerline.app.data.remote.dto.PaperlessConfigRequest(enabled = enabled, url = url.ifBlank { null }, token = token?.ifBlank { null })
        return try { apiProvider(s).updatePaperlessConfig(body).takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
    suspend fun testPaperless(): Boolean {
        val s = sessionHolder.get() ?: return false
        return try { apiProvider(s).testPaperlessConfig().takeIf { it.isSuccessful }?.body()?.ok == true } catch (_: Exception) { false }
    }
    suspend fun paperlessSync(): Boolean {
        val s = sessionHolder.get() ?: return false
        return try { apiProvider(s).paperlessSync().takeIf { it.isSuccessful }?.body()?.ok == true } catch (_: Exception) { false }
    }
    /** Forward a document's bytes to the user's Paperless-ngx (transient; server stores nothing). */
    suspend fun paperlessSubmit(bytes: ByteArray, fileName: String, mime: String): Boolean {
        val s = sessionHolder.get() ?: return false
        return try {
            val part = okhttp3.MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            apiProvider(s).paperlessSubmit(part).takeIf { it.isSuccessful }?.body()?.ok == true
        } catch (_: Exception) { false }
    }

    suspend fun resendEmailVerification(): Boolean = call { it.resendEmailVerification() }

    /** CLI-style device pairing code (owner approves another device). */
    suspend fun createDevicePairingCli(): de.ledgerline.app.data.remote.dto.DevicePairingCreated? {
        val s = sessionHolder.get() ?: return null
        return try { apiProvider(s).createDevicePairingCli().takeIf { it.isSuccessful }?.body() } catch (_: Exception) { null }
    }
}
