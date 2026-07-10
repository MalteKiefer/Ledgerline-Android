package de.ledgerline.app.data

import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.PinnedTrust
import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.domain.usecase.PairingGateway
import de.ledgerline.app.domain.usecase.PollResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection
import java.security.cert.X509Certificate

/**
 * Real pairing gateway. The pin is unknown until first contact, so claim/poll run
 * over an unpinned but HTTPS + system-CA-validated connection, and the leaf SPKI
 * is captured from the TLS handshake to return as the TOFU pin.
 */
class PairingRepository : PairingGateway {

    private fun api(baseUrl: String) = NetworkFactory.create(baseUrl, tokenProvider = { null }, pin = null)

    override suspend fun claim(baseUrl: String, code: String, deviceName: String): PollResult {
        return try {
            val res = api(baseUrl).claimPair(PairClaimRequest(code, deviceName))
            when (res.code()) {
                HttpURLConnection.HTTP_OK, 202 -> PollResult.Pending
                HttpURLConnection.HTTP_GONE -> PollResult.Gone
                429 -> PollResult.RateLimited
                else -> PollResult.NetworkError
            }
        } catch (_: Exception) { PollResult.NetworkError }
    }

    override suspend fun poll(baseUrl: String, code: String): PollResult {
        return try {
            val res = api(baseUrl).pollPair(code)
            when {
                res.code() == HttpURLConnection.HTTP_GONE -> PollResult.Gone
                res.code() == 429 -> PollResult.RateLimited
                !res.isSuccessful -> PollResult.NetworkError
                res.body()?.status == "approved" && res.body()?.token != null ->
                    PollResult.Approved(res.body()!!.token!!, capturePin(baseUrl), res.body()!!.user?.name)
                else -> PollResult.Pending
            }
        } catch (_: Exception) { PollResult.NetworkError }
    }

    /** Opens one TLS connection and hashes the leaf cert's SPKI (TOFU). */
    private suspend fun capturePin(baseUrl: String): String = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl()
        val client = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .build()
        client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
            val handshake = resp.handshake ?: throw IOException("no TLS handshake")
            val leaf = handshake.peerCertificates.first() as X509Certificate
            PinnedTrust.spkiSha256Base64(leaf)
        }
    }
}
