package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import retrofit2.Response

/**
 * Shared optimistic-write + cache-fallback helpers for the sealed `/store` and
 * `/gallery/store` endpoints. Both stores share the exact same envelope
 * (`{ciphertext, version}`), the same 409 optimistic-concurrency loop, and the
 * same network-first on-disk fallback — only the manifest type, cache, API
 * methods, and domain wrapper differ, so those are passed in as lambdas.
 *
 * NOTE: gallery *load* has extra v2-shard assembly which is NOT shared and lives
 * in GalleryRepository; only [optimisticSave] and [cachedOrStore] are common.
 */

/**
 * Decode a sealed envelope's ciphertext into a manifest [M], or the empty manifest
 * when the ciphertext is null. Returns null when the open/decode fails (caller maps
 * that to its own error). [open] returns the plaintext JSON or null on decrypt failure.
 */
private inline fun <M> decodeManifest(
    ciphertext: String?,
    open: (String) -> String?,
    decode: (String) -> M,
    empty: () -> M,
): M? {
    if (ciphertext == null) return empty()
    val plain = open(ciphertext) ?: return null
    return decode(plain)
}

/**
 * Optimistic write: apply [mutate] to the current manifest, PUT it; on 409 reload
 * the server manifest, re-apply [mutate], and retry (bounded to 4 attempts). Updates
 * the cache on success. Behaviour matches the previous per-repo copies exactly.
 *
 * @param cached the in-memory (manifest, version) if present, else null → force reload.
 * @param fetch  GET the sealed store envelope.
 * @param put    PUT the sealed ciphertext at the given version.
 * @param seal   seal a manifest into its ciphertext.
 * @param open   open a ciphertext into plaintext JSON (null on decrypt failure).
 * @param decode parse plaintext JSON into a manifest.
 * @param empty  the empty manifest (null ciphertext).
 * @param wrap   build the domain wrapper (manifest, version).
 * @param onSaved cache the result on success.
 * @param onEnvelope persist the sealed envelope on success (offline cache).
 */
internal suspend inline fun <M, W> optimisticSave(
    cached: Pair<M, Int>?,
    mutate: (M) -> M,
    fetch: suspend () -> Response<StoreResponse>,
    put: suspend (StorePutRequest) -> Response<StoreResponse>,
    seal: (M) -> String,
    open: (String) -> String?,
    decode: (String) -> M,
    empty: () -> M,
    wrap: (M, Int) -> W,
    onSaved: (W) -> Unit,
    onEnvelope: (StoreEnvelope) -> Unit,
): Outcome<W> {
    // DATA-LOSS FIX: never seed the write base from a cached (manifest, version) pair.
    // The two can drift — e.g. a concurrent/replay write bumps the tracked version without
    // refreshing the cached manifest — and then a PUT whose stale version happens to match
    // the server overwrites the server's records with NO 409 (silent clobber; this is how
    // Health/Explore records vanished). Always fetch the current (content, version) together
    // before the first PUT so a version-matched write is only ever additive. `cached` is
    // intentionally ignored for the base; it stays in the signature for call-site stability.
    @Suppress("UNUSED_EXPRESSION") cached
    var base: M? = null
    var version: Int? = null

    repeat(5) {
        if (base == null || version == null) {
            val res = fetch()
            if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
            val body = res.body()!!
            base = decodeManifest(body.ciphertext, open, decode, empty)
                ?: return Outcome.Err(ErrorKind.DECRYPT)
            version = body.version
        }

        val next = mutate(base!!)
        val ciphertext = seal(next)
        val putRes = try {
            put(StorePutRequest(ciphertext, version!!))
        } catch (e: Exception) {
            return Outcome.Err(ErrorKind.NETWORK, e)
        }

        when {
            putRes.isSuccessful -> {
                val newVersion = putRes.body()?.version ?: (version!! + 1)
                val wrapped = wrap(next, newVersion)
                onSaved(wrapped)
                onEnvelope(StoreEnvelope(ciphertext, newVersion))
                return Outcome.Ok(wrapped)
            }
            putRes.code() == 409 -> {
                // Reload fresh server state, then loop to re-apply mutate.
                val res = fetch()
                if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                val body = res.body()!!
                base = decodeManifest(body.ciphertext, open, decode, empty)
                    ?: return Outcome.Err(ErrorKind.DECRYPT)
                version = body.version
            }
            else -> return Outcome.Err(ErrorKind.HTTP)
        }
    }
    return Outcome.Err(ErrorKind.HTTP) // gave up after retries
}

/**
 * Network-first cache fallback: when a fetch fails with a non-auth error, try the
 * on-disk sealed [envelope] and decrypt it in-memory. Returns [err] unchanged if
 * offline caching is off, no entry exists, or decryption fails.
 */
internal inline fun <M, W> cachedOrStore(
    cachingEnabled: Boolean,
    envelope: StoreEnvelope?,
    err: Outcome<W>,
    open: (String) -> String?,
    decode: (String) -> M,
    empty: () -> M,
    wrap: (M, Int) -> W,
): Outcome<W> {
    if (!cachingEnabled) return err
    val env = envelope ?: return err
    return try {
        val manifest = decodeManifest(env.ciphertext, open, decode, empty) ?: return err
        Outcome.Ok(wrap(manifest, env.version))
    } catch (_: Exception) {
        err
    }
}
