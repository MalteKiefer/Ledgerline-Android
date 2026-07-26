package de.ledgerline.app.data

import kotlinx.coroutines.delay
import retrofit2.Response

/**
 * Delete freed blobs, honoring `Retry-After` on 429 (backoff capped at 30 s,
 * max 3 attempts per ref). Sequential — bulk sizes here are small.
 *
 * Shared by the file/gallery/contact blob repos. Each caller passes its own
 * [delete] call and, optionally, an [onRemoveCache] hook (contacts evict the
 * on-disk ciphertext per ref). The blank/distinct filter is applied for all.
 */
suspend fun deleteBlobsWithBackoff(
    refs: List<String>,
    onRemoveCache: (String) -> Unit = {},
    delete: suspend (String) -> Response<Unit>,
) {
    for (ref in refs.filter { it.isNotBlank() }.distinct()) {
        onRemoveCache(ref)
        var attempt = 0
        while (attempt < 3) {
            val res = try { delete(ref) } catch (_: Exception) { break }
            if (res.code() == 429) {
                val retryAfterMs = res.headers()["Retry-After"]?.toLongOrNull()?.times(1000)
                    ?: (1000L shl attempt)
                delay(minOf(retryAfterMs, 30_000L))
                attempt++
            } else {
                break
            }
        }
    }
}
