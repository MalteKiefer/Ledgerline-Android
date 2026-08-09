package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.admin.AdminGroupResponse
import de.ledgerline.app.domain.model.admin.AdminGroupsResponse
import de.ledgerline.app.domain.model.admin.AdminMessage
import de.ledgerline.app.domain.model.admin.AdminOk
import de.ledgerline.app.domain.model.admin.AdminUserResponse
import de.ledgerline.app.domain.model.admin.AdminUsersResponse
import de.ledgerline.app.domain.model.admin.DevicePolicy
import de.ledgerline.app.domain.model.admin.FilesLimits
import de.ledgerline.app.domain.model.admin.InviteLinkResponse
import de.ledgerline.app.domain.model.admin.RegistrationSetting
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path

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
    suspend fun notifications(): Response<JsonObject>

    @PUT("api/v1/admin/notifications")
    suspend fun updateNotifications(@Body body: JsonObject): Response<JsonObject>

    @POST("api/v1/admin/notifications/test")
    suspend fun testNotification(@Body body: JsonObject): Response<AdminOk>

    // ---- System (read-only ops) ----
    @GET("api/v1/admin/system")
    suspend fun system(): Response<JsonObject>

    @POST("api/v1/admin/system/errors/{id}/resolve")
    suspend fun resolveError(@Path("id") id: Int): Response<AdminOk>
}
