package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.admin.AdminGroupResponse
import de.ledgerline.app.domain.model.admin.AdminGroupsResponse
import de.ledgerline.app.domain.model.admin.AdminMessage
import de.ledgerline.app.domain.model.admin.AdminOk
import de.ledgerline.app.domain.model.admin.AdminUserResponse
import de.ledgerline.app.domain.model.admin.AdminUsersResponse
import de.ledgerline.app.domain.model.admin.DevicePolicy
import de.ledgerline.app.domain.model.admin.FilesLimits
import de.ledgerline.app.domain.model.admin.BackupDestinationsResponse
import de.ledgerline.app.domain.model.admin.BackupJobResponse
import de.ledgerline.app.domain.model.admin.BackupJobsResponse
import de.ledgerline.app.domain.model.admin.BackupRunsResponse
import de.ledgerline.app.domain.model.admin.InviteLinkResponse
import de.ledgerline.app.domain.model.admin.NotificationsSettings
import de.ledgerline.app.domain.model.admin.RegistrationSetting
import de.ledgerline.app.domain.model.admin.SecurityLogPage
import de.ledgerline.app.domain.model.admin.SystemOverview
import okhttp3.ResponseBody
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Admin REST surface (`can:manage-global-settings`). Free-form [JsonObject] request bodies so the
 * repository controls exactly which fields (and secrets) it sends — a blank secret preserves the
 * stored value server-side. 204/empty responses use [Unit]. Backup is a separate, larger surface
 * (deferred). No client crypto — plaintext over TLS.
 */
interface AdminApi {
    // ---- Users ----
    @GET("api/v1/users")
    suspend fun users(): Response<AdminUsersResponse>

    @POST("api/v1/users")
    suspend fun createUser(@Body body: JsonObject): Response<AdminUserResponse>

    @PUT("api/v1/users/{id}")
    suspend fun updateUser(@Path("id") id: Int, @Body body: JsonObject): Response<AdminUserResponse>

    @DELETE("api/v1/users/{id}")
    suspend fun deleteUser(@Path("id") id: Int): Response<Unit>

    @POST("api/v1/users/{id}/reset-password")
    suspend fun resetUserPassword(@Path("id") id: Int): Response<AdminMessage>

    @POST("api/v1/users/{id}/reset-2fa")
    suspend fun resetUserTwoFactor(@Path("id") id: Int): Response<AdminMessage>

    @POST("api/v1/users/{id}/invite-link")
    suspend fun inviteLink(@Path("id") id: Int, @Body body: JsonObject): Response<InviteLinkResponse>

    // ---- Groups ----
    @GET("api/v1/groups")
    suspend fun groups(): Response<AdminGroupsResponse>

    @POST("api/v1/groups")
    suspend fun createGroup(@Body body: JsonObject): Response<AdminGroupResponse>

    @PUT("api/v1/groups/{id}")
    suspend fun updateGroup(@Path("id") id: Int, @Body body: JsonObject): Response<AdminGroupResponse>

    @DELETE("api/v1/groups/{id}")
    suspend fun deleteGroup(@Path("id") id: Int): Response<Unit>

    // ---- Workspace access ----
    @GET("api/v1/admin/registration")
    suspend fun registration(): Response<RegistrationSetting>

    @PUT("api/v1/admin/registration")
    suspend fun updateRegistration(@Body body: JsonObject): Response<RegistrationSetting>

    @GET("api/v1/admin/security")
    suspend fun devicePolicy(): Response<DevicePolicy>

    @PUT("api/v1/admin/security")
    suspend fun updateDevicePolicy(@Body body: JsonObject): Response<DevicePolicy>

    @GET("api/v1/admin/files-limits")
    suspend fun filesLimits(): Response<FilesLimits>

    @PUT("api/v1/admin/files-limits")
    suspend fun updateFilesLimits(@Body body: JsonObject): Response<FilesLimits>

    // ---- Notifications (SMTP/NTFY/webhook) ----
    @GET("api/v1/admin/notifications")
    suspend fun notifications(): Response<NotificationsSettings>

    @PUT("api/v1/admin/notifications")
    suspend fun updateNotifications(@Body body: JsonObject): Response<NotificationsSettings>

    @POST("api/v1/admin/notifications/test")
    suspend fun testNotification(@Body body: JsonObject): Response<AdminOk>

    // ---- System (read-only ops) ----
    @GET("api/v1/admin/system")
    suspend fun system(): Response<SystemOverview>

    @POST("api/v1/admin/system/errors/{id}/resolve")
    suspend fun resolveError(@Path("id") id: Int): Response<AdminOk>

    // ---- Security log ----
    @GET("api/v1/security-log")
    suspend fun securityLog(
        @Query("action") action: String?,
        @Query("user") user: Int?,
        @Query("since") since: String?,
        @Query("page") page: Int?,
        @Query("per_page") perPage: Int?,
    ): Response<SecurityLogPage>

    @GET("api/v1/security-log/export")
    @Streaming
    suspend fun securityLogExport(@Query("format") format: String, @Query("limit") limit: Int?): Response<ResponseBody>

    // ---- Backup: destinations ----
    @GET("api/v1/backup/destinations")
    suspend fun backupDestinations(): Response<BackupDestinationsResponse>

    @POST("api/v1/backup/destinations")
    suspend fun createBackupDestination(@Body body: JsonObject): Response<JsonObject>

    @PUT("api/v1/backup/destinations/{id}")
    suspend fun updateBackupDestination(@Path("id") id: Int, @Body body: JsonObject): Response<JsonObject>

    @DELETE("api/v1/backup/destinations/{id}")
    suspend fun deleteBackupDestination(@Path("id") id: Int): Response<Unit>

    @POST("api/v1/backup/destinations/test")
    suspend fun testBackupDestination(@Body body: JsonObject): Response<AdminOk>

    // ---- Backup: jobs ----
    @GET("api/v1/backup/jobs")
    suspend fun backupJobs(): Response<BackupJobsResponse>

    @POST("api/v1/backup/jobs")
    suspend fun createBackupJob(@Body body: JsonObject): Response<BackupJobResponse>

    @PUT("api/v1/backup/jobs/{id}")
    suspend fun updateBackupJob(@Path("id") id: Int, @Body body: JsonObject): Response<BackupJobResponse>

    @DELETE("api/v1/backup/jobs/{id}")
    suspend fun deleteBackupJob(@Path("id") id: Int): Response<Unit>

    @POST("api/v1/backup/jobs/{id}/run")
    suspend fun runBackupJob(@Path("id") id: Int): Response<AdminOk>

    // ---- Backup: runs ----
    @GET("api/v1/backup/runs")
    suspend fun backupRuns(): Response<BackupRunsResponse>

    @GET("api/v1/backup/runs/{id}/download")
    @Streaming
    suspend fun downloadBackupRun(@Path("id") id: Int, @Query("source") source: String): Response<ResponseBody>

    @POST("api/v1/backup/runs/{id}/verify")
    suspend fun verifyBackupRun(@Path("id") id: Int, @Body body: JsonObject): Response<AdminOk>

    @POST("api/v1/backup/runs/{id}/cancel")
    suspend fun cancelBackupRun(@Path("id") id: Int): Response<AdminOk>

    @POST("api/v1/backup/runs/{id}/decrypt")
    @Streaming
    suspend fun decryptBackupRun(@Path("id") id: Int, @Body body: JsonObject): Response<ResponseBody>

    @POST("api/v1/backup/runs/{id}/restore")
    suspend fun restoreBackupRun(@Path("id") id: Int, @Body body: JsonObject): Response<AdminOk>

    // ---- Security portal (request log + blocked IPs + user block) ----
    @GET("api/v1/request-log")
    suspend fun requestLog(@Query("page") page: Int?, @Query("per_page") perPage: Int?): Response<de.ledgerline.app.domain.model.admin.RequestLogPage>

    @GET("api/v1/request-log/export")
    @Streaming
    suspend fun requestLogExport(@Query("format") format: String): Response<ResponseBody>

    @GET("api/v1/blocked-ips")
    suspend fun blockedIps(): Response<de.ledgerline.app.domain.model.admin.BlockedIpsResponse>

    @POST("api/v1/blocked-ips")
    suspend fun blockIp(@Body body: JsonObject): Response<JsonObject>

    @DELETE("api/v1/blocked-ips/{id}")
    suspend fun unblockIp(@Path("id") id: Int): Response<Unit>

    @POST("api/v1/users/{id}/block")
    suspend fun blockUser(@Path("id") id: Int): Response<AdminOk>

    @POST("api/v1/users/{id}/unblock")
    suspend fun unblockUser(@Path("id") id: Int): Response<AdminOk>
}
