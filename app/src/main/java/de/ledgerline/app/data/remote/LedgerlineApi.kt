package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.LoginRequest
import de.ledgerline.app.data.remote.dto.LoginResponse
import de.ledgerline.app.data.remote.dto.MeResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Account + device REST surface for the finance pivot: pairing, `/me`, avatar, devices, notifications,
 * preferences/locale/theme/settings, account export/delete, 2FA, and password. Finance records live
 * in [FinanceApi]. (Trimmed from the old zero-knowledge interface — every store/vault/blob endpoint
 * was deleted with the ZK modules.)
 */
interface LedgerlineApi {
    // ---- Login (public) — email + password (+ 2FA) → device-scoped bearer ----
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    // ---- Account / device ----
    @GET("api/v1/me")
    suspend fun me(): Response<MeResponse>

    @GET("api/v1/avatar")
    @Streaming
    suspend fun avatar(): Response<ResponseBody>

    @POST("api/v1/device/heartbeat")
    suspend fun deviceHeartbeat(@Body body: de.ledgerline.app.data.remote.dto.HeartbeatRequest): Response<de.ledgerline.app.data.remote.dto.HeartbeatResponse>

    @GET("api/v1/devices")
    suspend fun devices(): Response<de.ledgerline.app.data.remote.dto.DevicesResponse>

    @DELETE("api/v1/devices/{token}")
    suspend fun revokeDevice(@Path("token") token: String): Response<Unit>

    @POST("api/v1/devices/{token}/wipe")
    suspend fun wipeDevice(@Path("token") token: String): Response<Unit>

    @DELETE("api/v1/auth/session")
    suspend fun deleteSession(): Response<Unit>

    // ---- Notifications ----
    @GET("api/v1/notifications")
    suspend fun notifications(@Header("If-None-Match") etag: String?): Response<de.ledgerline.app.data.remote.dto.NotificationsResponse>

    @POST("api/v1/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: Long): Response<Unit>

    @POST("api/v1/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<Unit>

    // ---- Preferences / settings ----
    @POST("api/v1/preferences")
    suspend fun putPreferences(@Body body: de.ledgerline.app.data.remote.dto.DisplayPrefsDto): Response<Unit>

    @POST("api/v1/locale")
    suspend fun putLocale(@Body body: de.ledgerline.app.data.remote.dto.LocaleRequest): Response<Unit>

    @POST("api/v1/theme")
    suspend fun putTheme(@Body body: de.ledgerline.app.data.remote.dto.ThemeRequest): Response<Unit>

    @GET("api/v1/settings")
    suspend fun getSettings(): Response<de.ledgerline.app.data.remote.dto.UserSettingsDto>

    @PUT("api/v1/settings")
    suspend fun putSettings(@Body body: de.ledgerline.app.data.remote.dto.UserSettingsDto): Response<de.ledgerline.app.data.remote.dto.UserSettingsDto>

    // ---- Account control ----
    @GET("api/v1/account/export")
    @Streaming
    suspend fun accountExport(): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "api/v1/account", hasBody = true)
    suspend fun deleteAccount(@Body body: de.ledgerline.app.data.remote.dto.DeleteAccountRequest): Response<de.ledgerline.app.data.remote.dto.DeleteAccountResponse>

    @PUT("api/v1/user/password")
    suspend fun changePassword(@Body body: de.ledgerline.app.data.remote.dto.ChangePasswordRequest): Response<Unit>

    // ---- Two-factor (v1.562.0: enable/recovery-codes/regenerate/disable require password step-up) ----
    @POST("api/v1/user/two-factor/enable")
    suspend fun twoFactorEnable(@Body body: de.ledgerline.app.data.remote.dto.CurrentPasswordRequest): Response<de.ledgerline.app.data.remote.dto.TwoFactorEnabledResponse>

    @GET("api/v1/user/two-factor/qr")
    suspend fun twoFactorQr(): Response<de.ledgerline.app.data.remote.dto.TwoFactorQrResponse>

    @POST("api/v1/user/two-factor/confirm")
    suspend fun twoFactorConfirm(@Body body: de.ledgerline.app.data.remote.dto.TwoFactorConfirmRequest): Response<Unit>

    // The server reads current_password via Laravel input() (query OR body). OkHttp forbids a GET
    // request body, so we pass it as a query param (transported over TLS).
    @GET("api/v1/user/two-factor/recovery-codes")
    suspend fun twoFactorRecoveryCodes(@retrofit2.http.Query("current_password") currentPassword: String): Response<de.ledgerline.app.data.remote.dto.RecoveryCodesResponse>

    @POST("api/v1/user/two-factor/recovery-codes/regenerate")
    suspend fun twoFactorRegenerateRecoveryCodes(@Body body: de.ledgerline.app.data.remote.dto.CurrentPasswordRequest): Response<de.ledgerline.app.data.remote.dto.RecoveryCodesResponse>

    @HTTP(method = "DELETE", path = "api/v1/user/two-factor", hasBody = true)
    suspend fun twoFactorDisable(@Body body: de.ledgerline.app.data.remote.dto.CurrentPasswordRequest): Response<de.ledgerline.app.data.remote.dto.TwoFactorEnabledResponse>

    // ---- Account sessions ----
    @GET("api/v1/account/sessions")
    suspend fun accountSessions(): Response<de.ledgerline.app.data.remote.dto.SessionsResponse>

    @DELETE("api/v1/account/sessions/{id}")
    suspend fun revokeAccountSession(@Path("id") id: String): Response<Unit>

    // ---- App-specific WebDAV mount password ----
    @GET("api/v1/account/webdav")
    suspend fun webdav(): Response<de.ledgerline.app.data.remote.dto.WebDavStatus>

    @PUT("api/v1/account/webdav")
    suspend fun updateWebdav(@Body body: de.ledgerline.app.data.remote.dto.WebDavRequest): Response<de.ledgerline.app.data.remote.dto.WebDavStatus>

    @DELETE("api/v1/account/webdav")
    suspend fun clearWebdav(): Response<de.ledgerline.app.data.remote.dto.WebDavStatus>

    // ---- Owner-side device pairing (approve a new device) ----
    @POST("api/v1/device-pairings")
    suspend fun createDevicePairing(): Response<de.ledgerline.app.data.remote.dto.DevicePairingCreated>

    @GET("api/v1/device-pairings/{id}")
    suspend fun devicePairingStatus(@Path("id") id: Long): Response<de.ledgerline.app.data.remote.dto.DevicePairingStatus>

    @POST("api/v1/device-pairings/{id}/approve")
    suspend fun approveDevicePairing(@Path("id") id: Long): Response<Unit>

    @POST("api/v1/device-pairings/{id}/reject")
    suspend fun rejectDevicePairing(@Path("id") id: Long): Response<Unit>

    // ---- Paperless-ngx integration ----
    @GET("api/v1/paperless/config")
    suspend fun paperlessConfig(): Response<de.ledgerline.app.data.remote.dto.PaperlessConfig>

    @PUT("api/v1/paperless/config")
    suspend fun updatePaperlessConfig(@Body body: de.ledgerline.app.data.remote.dto.PaperlessConfigRequest): Response<de.ledgerline.app.data.remote.dto.PaperlessConfig>

    @POST("api/v1/paperless/config/test")
    suspend fun testPaperlessConfig(): Response<de.ledgerline.app.data.remote.dto.PaperlessOk>

    @GET("api/v1/paperless/terms")
    suspend fun paperlessTerms(): Response<de.ledgerline.app.data.remote.dto.PaperlessTermsResponse>

    @POST("api/v1/paperless/sync")
    suspend fun paperlessSync(): Response<de.ledgerline.app.data.remote.dto.PaperlessOk>

    // ---- Site-icon proxy (finance bank logos / partner favicons) ----
    @GET("api/v1/passwords/icon")
    @Streaming
    suspend fun siteIcon(@retrofit2.http.Query("url") url: String): Response<ResponseBody>

    // ---- Misc account/integration endpoints (completeness) ----
    @POST("api/v1/user/email/verify/resend")
    suspend fun resendEmailVerification(): Response<Unit>

    @retrofit2.http.Multipart
    @POST("api/v1/paperless/documents")
    suspend fun paperlessSubmit(@retrofit2.http.Part file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.PaperlessOk>

    @POST("api/v1/device-pairings/cli")
    suspend fun createDevicePairingCli(): Response<de.ledgerline.app.data.remote.dto.DevicePairingCreated>
}
