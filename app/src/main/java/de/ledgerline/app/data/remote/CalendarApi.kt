package de.ledgerline.app.data.remote

import de.ledgerline.app.domain.model.calendar.CalendarDataResponse
import de.ledgerline.app.domain.model.calendar.CalendarTodo
import de.ledgerline.app.domain.model.calendar.TodoCreated
import de.ledgerline.app.domain.model.calendar.TodoImportResult
import de.ledgerline.app.domain.model.calendar.TodosResponse
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The task-list (VTODO) slice of the server calendar module. Only the endpoints the "Aufgaben" tab
 * needs: `/calendar/data` for the task lists, `/calendar/todos` CRUD + reorder + ICS import/export, and
 * `/calendars` to create a task list on demand. Bodies are free-form [JsonObject] (CalendarTodoInput).
 */
interface CalendarApi {
    @GET("api/v1/calendar/data")
    suspend fun data(): Response<CalendarDataResponse>

    @GET("api/v1/calendar/todos")
    suspend fun todos(
        @Query("calendar_id") calendarId: String? = null,
        @Query("status") status: String? = null,
        @Query("expand") expand: Int? = null,
    ): Response<TodosResponse>

    @GET("api/v1/calendar/todos/{id}")
    suspend fun todo(@Path("id") id: String): Response<CalendarTodo>

    @POST("api/v1/calendar/todos")
    suspend fun createTodo(@Body body: JsonObject): Response<TodoCreated>

    @PUT("api/v1/calendar/todos/{id}")
    suspend fun updateTodo(@Path("id") id: String, @Body body: JsonObject): Response<JsonObject>

    @DELETE("api/v1/calendar/todos/{id}")
    suspend fun deleteTodo(@Path("id") id: String): Response<Unit>

    @POST("api/v1/calendar/todos/reorder")
    suspend fun reorder(@Body body: JsonObject): Response<Unit>

    /** Create a calendar collection; send {name, component:"VTODO"} for a task list. */
    @POST("api/v1/calendars")
    suspend fun createCalendar(@Body body: JsonObject): Response<JsonObject>

    @GET("api/v1/calendar/todos/export")
    @Streaming
    suspend fun exportTodos(@Query("calendar_id") calendarId: String? = null): Response<ResponseBody>

    @Multipart
    @POST("api/v1/calendar/todos/import")
    suspend fun importTodos(
        @Part file: MultipartBody.Part,
        @Part("calendar_id") calendarId: RequestBody,
    ): Response<TodoImportResult>
}
