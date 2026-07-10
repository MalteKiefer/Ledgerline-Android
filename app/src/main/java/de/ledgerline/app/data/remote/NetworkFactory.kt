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
 * The `internal` overload allows CLEARTEXT solely for JVM unit tests hitting a
 * plain-HTTP MockWebServer; the public API can never enable it.
 */
object NetworkFactory {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(baseUrl: String, tokenProvider: () -> String?, pin: String?): LedgerlineApi =
        create(baseUrl, tokenProvider, pin, allowCleartext = false)

    internal fun create(
        baseUrl: String,
        tokenProvider: () -> String?,
        pin: String?,
        allowCleartext: Boolean,
    ): LedgerlineApi {
        val host = baseUrl.toHttpUrl().host
        val specs = if (allowCleartext) {
            listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT)
        } else {
            listOf(ConnectionSpec.RESTRICTED_TLS)
        }
        val builder = OkHttpClient.Builder()
            .connectionSpecs(specs)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider))
            // Detect an authenticated 401 (revoked token) and signal a forced
            // logout. Placed after AuthInterceptor so the request it inspects already
            // carries the Bearer header; pairing/poll calls have no Authorization
            // header, so their 401s never fire this.
            .addInterceptor { chain ->
                val r = chain.proceed(chain.request())
                if (r.code == 401 && chain.request().header("Authorization") != null) {
                    AuthNotifier.onUnauthorized?.invoke()
                }
                r
            }
            .addInterceptor(BackoffInterceptor())
        if (pin != null) builder.certificatePinner(PinnedTrust.pinnerFor(host, pin))
        val retrofit = Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(builder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(LedgerlineApi::class.java)
    }
}
