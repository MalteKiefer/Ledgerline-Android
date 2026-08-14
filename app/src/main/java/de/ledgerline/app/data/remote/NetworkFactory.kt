package de.ledgerline.app.data.remote

import de.ledgerline.app.core.AuthNotifier
import de.ledgerline.app.data.remote.interceptors.AuthInterceptor
import de.ledgerline.app.data.remote.interceptors.BackoffInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds a Retrofit client bound to a specific base URL. `pin` is null during the
 * initial pairing claim/poll (TOFU: pin not yet known); once known, all sessions
 * use a pinned client.
 *
 * Production traffic is HTTPS-only: the client is restricted to RESTRICTED_TLS
 * (TLS 1.2+/strong ciphers) and cleartext is never permitted here — the platform
 * network_security_config additionally blocks it app-wide (defense in depth).
 * The public API can never enable cleartext; the `internal` [build] seam takes the
 * connection specs so JVM unit tests (in this package) can add CLEARTEXT to hit a
 * plain-HTTP MockWebServer.
 */
object NetworkFactory {
    // coerceInputValues: several finance columns (customer/lines/versions/receipts/expenses/
    // contacts) are nullable arrays/objects server-side; coerce a JSON null on a non-null
    // defaulted field to its default instead of throwing.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Production entry point: HTTPS-only (RESTRICTED_TLS), optionally pinned. */
    fun create(baseUrl: String, tokenProvider: () -> String?, pin: String?): LedgerlineApi =
        build(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))

    /** The plaintext-relational finance API over the same pinned, authenticated transport. */
    fun createFinance(baseUrl: String, tokenProvider: () -> String?, pin: String?): FinanceApi =
        retrofitFor(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))
            .create(FinanceApi::class.java)

    /** The plaintext-relational files API over the same pinned, authenticated transport. */
    fun createFiles(baseUrl: String, tokenProvider: () -> String?, pin: String?): FilesApi =
        retrofitFor(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))
            .create(FilesApi::class.java)

    /** The admin API (workspace settings/users/groups) over the same pinned, authenticated transport. */
    fun createAdmin(baseUrl: String, tokenProvider: () -> String?, pin: String?): AdminApi =
        retrofitFor(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))
            .create(AdminApi::class.java)

    /** The task-list (VTODO) slice of the calendar module over the same pinned, authenticated transport. */
    fun createCalendar(baseUrl: String, tokenProvider: () -> String?, pin: String?): CalendarApi =
        retrofitFor(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))
            .create(CalendarApi::class.java)

    fun createNotes(baseUrl: String, tokenProvider: () -> String?, pin: String?): NotesApi =
        retrofitFor(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))
            .create(NotesApi::class.java)

    fun createGallery(baseUrl: String, tokenProvider: () -> String?, pin: String?): GalleryApi =
        retrofitFor(baseUrl, tokenProvider, pin, listOf(ConnectionSpec.RESTRICTED_TLS))
            .create(GalleryApi::class.java)

    /**
     * A minimal HTTPS client (RESTRICTED_TLS + SPKI pinning, short timeouts, no auth) for the
     * server-reachability health ping (`GET {baseUrl}/up`). Same fail-closed transport as the API,
     * so a MITM can't fake "online".
     */
    fun pingClient(baseUrl: String, pin: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        if (pin != null) builder.certificatePinner(PinnedTrust.pinnerFor(baseUrl.toHttpUrl().host, pin))
        return builder.build()
    }

    /**
     * Shared wiring seam: builds the interceptor chain + Retrofit client for the given
     * [connectionSpecs]. Production always passes RESTRICTED_TLS only; tests may add
     * CLEARTEXT. Kept `internal` so cleartext can never leak into the public surface.
     */
    internal fun build(
        baseUrl: String,
        tokenProvider: () -> String?,
        pin: String?,
        connectionSpecs: List<ConnectionSpec>,
    ): LedgerlineApi = retrofitFor(baseUrl, tokenProvider, pin, connectionSpecs).create(LedgerlineApi::class.java)

    /** Builds the shared interceptor chain + Retrofit client (used for every API interface). */
    private fun retrofitFor(
        baseUrl: String,
        tokenProvider: () -> String?,
        pin: String?,
        connectionSpecs: List<ConnectionSpec>,
        callTimeoutSeconds: Long = 60,
    ): Retrofit {
        val host = baseUrl.toHttpUrl().host
        val builder = OkHttpClient.Builder()
            .connectionSpecs(connectionSpecs)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider))
            // Detect an authenticated 401 (revoked token) and signal a forced
            // logout. Placed after AuthInterceptor so the request it inspects already
            // carries the Bearer header; pairing/poll calls have no Authorization
            // header, so their 401s never fire this.
            .addInterceptor { chain ->
                val r = chain.proceed(chain.request())
                val authed = chain.request().header("Authorization") != null
                if (r.code == 401 && authed) {
                    AuthNotifier.onUnauthorized?.invoke()
                } else if (r.code == 403 && authed) {
                    // Workspace force-2FA: 403 {status:"two_factor_required"} on every gated
                    // endpoint until the user enrolls a second factor. Peek the (tiny) body so
                    // it is NOT consumed for the caller.
                    val peek = runCatching { r.peekBody(2048).string() }.getOrNull()
                    if (peek?.contains("two_factor_required") == true) {
                        AuthNotifier.onTwoFactorRequired?.invoke()
                    }
                }
                r
            }
            .addInterceptor(BackoffInterceptor())
        if (pin != null) builder.certificatePinner(PinnedTrust.pinnerFor(host, pin))
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(builder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
