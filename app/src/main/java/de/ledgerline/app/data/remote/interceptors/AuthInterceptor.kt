package de.ledgerline.app.data.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Response

/** Adds Bearer auth to /api/v1 calls except the public /auth/pair claim+poll. */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val isPublic = req.url.encodedPath.endsWith("/api/v1/auth/pair")
        val token = tokenProvider()
        val out = if (!isPublic && token != null) {
            req.newBuilder().header("Authorization", "Bearer $token").build()
        } else req
        return chain.proceed(out)
    }
}
