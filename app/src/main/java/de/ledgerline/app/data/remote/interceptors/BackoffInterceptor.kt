package de.ledgerline.app.data.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Response

/** Retries on HTTP 429 honoring Retry-After, with capped exponential backoff. */
class BackoffInterceptor(
    private val maxRetries: Int = 3,
    private val maxDelayMs: Long = 30_000,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())
        while (response.code == 429 && attempt < maxRetries) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            // Cap the wait so a large or hostile Retry-After cannot stall the thread.
            val delayMs = minOf((retryAfter?.times(1000)) ?: (1000L shl attempt), maxDelayMs)
            response.close()
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }
}
