package de.ledgerline.app.data.remote

import okhttp3.ConnectionSpec

/**
 * Test-only entry point: builds a [LedgerlineApi] that also accepts CLEARTEXT so JVM
 * unit tests can drive a plain-HTTP MockWebServer. Lives in `src/test` (same package)
 * so it can reach [NetworkFactory.build]; the production surface stays HTTPS-only.
 */
internal fun cleartextApi(
    baseUrl: String,
    tokenProvider: () -> String?,
    pin: String? = null,
): LedgerlineApi =
    NetworkFactory.build(
        baseUrl,
        tokenProvider,
        pin,
        listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT),
    )
