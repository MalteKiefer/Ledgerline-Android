package de.ledgerline.app.data

import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.PinnedTrust
import de.ledgerline.app.data.remote.dto.LoginRequest
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.cert.X509Certificate

/** Outcome of a login attempt. */
sealed interface LoginOutcome {
    data class Success(val session: Session) : LoginOutcome
    data object TwoFactorRequired : LoginOutcome
    data object InvalidCredentials : LoginOutcome
    /** v1.562.0: the account's email is not verified yet — server withholds the bearer (HTTP 403). */
    data object EmailNotVerified : LoginOutcome
    data object NotHttps : LoginOutcome
    data object NetworkError : LoginOutcome
}

/**
 * Direct login (replaces QR device pairing): the user types the server URL + email + password
 * (+ optional 2FA code). Runs `POST /auth/login` over an unpinned but HTTPS + system-CA-validated
 * connection (the SPKI pin is unknown until first contact), captures the leaf SPKI as the TOFU pin,
 * and returns a [Session]. Device metadata (name/install/app/os) is sent so the server registers the
 * token under connected devices.
 */
class LoginRepository(
    private val installId: String = "",
    private val appVersion: String = "",
    private val osVersion: String = "",
    private val deviceName: String = "",
) {
    private fun api(baseUrl: String) = NetworkFactory.create(baseUrl, tokenProvider = { null }, pin = null)

    suspend fun login(baseUrl: String, email: String, password: String, code: String?): LoginOutcome {
        val url = normalize(baseUrl)
        if (!url.startsWith("https://")) return LoginOutcome.NotHttps
        return try {
            val res = api(url).login(
                LoginRequest(
                    email = email.trim(),
                    password = password,
                    code = code?.trim()?.ifBlank { null },
                    device_name = deviceName.ifBlank { null },
                    install_id = installId.ifBlank { null },
                    app_version = appVersion.ifBlank { null },
                    os_version = osVersion.ifBlank { null },
                ),
            )
            when {
                res.isSuccessful && res.body()?.token != null ->
                    LoginOutcome.Success(Session(url, res.body()!!.token!!, capturePin(url), res.body()!!.user?.name))
                // Email-not-verified: HTTP 403 {status:"verify-email"} — server withholds the bearer.
                res.code() == 403 && (res.errorBody()?.string()?.contains("verify-email") == true) -> LoginOutcome.EmailNotVerified
                // 2FA required arrives as HTTP 422 {two_factor:true} — a non-2xx body, so inspect the error body.
                res.code() == 422 && (res.errorBody()?.string()?.contains("two_factor") == true) -> LoginOutcome.TwoFactorRequired
                res.code() == 422 -> LoginOutcome.InvalidCredentials
                else -> LoginOutcome.NetworkError
            }
        } catch (_: Exception) {
            LoginOutcome.NetworkError
        }
    }

    /** Accept "host", "host/", or "https://host" — normalise to a trailing-slash-free https URL. */
    private fun normalize(raw: String): String {
        var s = raw.trim().removeSuffix("/")
        if (s.isNotEmpty() && !s.contains("://")) s = "https://$s"
        return s
    }

    /** Opens one TLS connection and hashes the leaf cert's SPKI (TOFU). */
    private suspend fun capturePin(baseUrl: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS)).build()
        client.newCall(Request.Builder().url(baseUrl.toHttpUrl()).head().build()).execute().use { resp ->
            val handshake = resp.handshake ?: throw IOException("no TLS handshake")
            PinnedTrust.spkiSha256Base64(handshake.peerCertificates.first() as X509Certificate)
        }
    }
}
