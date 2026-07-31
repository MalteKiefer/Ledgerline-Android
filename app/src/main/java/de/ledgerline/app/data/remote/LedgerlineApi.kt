package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.MeResponse
import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.data.remote.dto.PairClaimResponse
import de.ledgerline.app.data.remote.dto.PairPollResponse
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
    // ---- Pairing (public) ----
    @POST("api/v1/auth/pair")
    suspend fun claimPair(@Body body: PairClaimRequest): Response<PairClaimResponse>

    @POST("api/v1/auth/pair/collect")
    suspend fun pollPair(@Body body: de.ledgerline.app.data.remote.dto.PairCollectRequest): Response<PairPollResponse>

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

    // ---- Two-factor ----
    @POST("api/v1/user/two-factor/enable")
    suspend fun twoFactorEnable(): Response<de.ledgerline.app.data.remote.dto.TwoFactorEnabledResponse>

    @GET("api/v1/user/two-factor/qr")
    suspend fun twoFactorQr(): Response<de.ledgerline.app.data.remote.dto.TwoFactorQrResponse>

    @POST("api/v1/user/two-factor/confirm")
    suspend fun twoFactorConfirm(@Body body: de.ledgerline.app.data.remote.dto.TwoFactorConfirmRequest): Response<Unit>

    @GET("api/v1/user/two-factor/recovery-codes")
    suspend fun twoFactorRecoveryCodes(): Response<de.ledgerline.app.data.remote.dto.RecoveryCodesResponse>

    @POST("api/v1/user/two-factor/recovery-codes/regenerate")
    suspend fun twoFactorRegenerateRecoveryCodes(): Response<de.ledgerline.app.data.remote.dto.RecoveryCodesResponse>

    @HTTP(method = "DELETE", path = "api/v1/user/two-factor", hasBody = false)
    suspend fun twoFactorDisable(): Response<de.ledgerline.app.data.remote.dto.TwoFactorEnabledResponse>
}
