package de.ledgerline.app.data.remote

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
 * Note: CLEARTEXT is included alongside RESTRICTED_TLS so JVM unit tests using
 * MockWebServer over plain HTTP pass. Production traffic is HTTPS-only, enforced
 * app-wide by network_security_config.xml.
 */
object NetworkFactory {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(baseUrl: String, tokenProvider: () -> String?, pin: String?): LedgerlineApi {
        val host = baseUrl.toHttpUrl().host
        val builder = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT))
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider))
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
