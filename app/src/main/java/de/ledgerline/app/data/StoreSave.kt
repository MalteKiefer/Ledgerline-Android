package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.offline.ManifestMerge
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import retrofit2.Response

/**
 * Shared optimistic-write + cache-fallback helpers for the sealed `/store` and per-module
 * `/{module}/store` endpoints (e.g. Explore, Health, Finance). Every such store shares the
 * exact same envelope (`{ciphertext, version}`), the same 409 optimistic-concurrency loop,
 * and the same network-first on-disk fallback — only the manifest type, cache, API methods,
 * and domain wrapper differ, so those are passed in as lambdas.
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
    // 3-way rebase support: convert a manifest to/from its sealed JSON root. On a 409 the write is
    // NOT a blind re-PUT of our stale copy (which drops the winner's records) — instead our delta
    // (base → ours) is replayed onto the freshly-fetched winner via [ManifestMerge]. Defaults keep
    // the old "re-apply mutate" behaviour for callers that don't (yet) supply a JSON view.
    noinline toJson: ((M) -> kotlinx.serialization.json.JsonObject)? = null,
    noinline fromJson: ((kotlinx.serialization.json.JsonObject) -> M)? = null,
): Outcome<W> {
    // DATA-LOSS FIX: never seed the write base from a cached (manifest, version) pair — always fetch
    // the current (content, version) together so a version-matched write is only ever additive.
    @Suppress("UNUSED_EXPRESSION") cached

    // Load the base we mutate from (fetch-first).
    val res0 = fetch()
    if (!res0.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
    val body0 = res0.body()!!
    val base: M = decodeManifest(body0.ciphertext, open, decode, empty) ?: return Outcome.Err(ErrorKind.DECRYPT)
    val ours: M = mutate(base)

    var candidate: M = ours
    var version: Int = body0.version

    repeat(8) {
        val ciphertext = seal(candidate)
        val putRes = try {
            put(StorePutRequest(ciphertext, version))
        } catch (e: Exception) {
            return Outcome.Err(ErrorKind.NETWORK, e)
        }
        when {
            putRes.isSuccessful -> {
                val newVersion = putRes.body()?.version ?: (version + 1)
                val wrapped = wrap(candidate, newVersion)
                onSaved(wrapped)
                onEnvelope(StoreEnvelope(ciphertext, newVersion))
                return Outcome.Ok(wrapped)
            }
            putRes.code() == 409 -> {
                // Fetch the winning manifest and rebase OUR delta (base → ours) onto it.
                val res = fetch()
                if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
                val serverBody = res.body()!!
                val serverM: M = decodeManifest(serverBody.ciphertext, open, decode, empty)
                    ?: return Outcome.Err(ErrorKind.DECRYPT)
                version = serverBody.version
                candidate = if (toJson != null && fromJson != null) {
                    fromJson(ManifestMerge.mergeManifest(toJson(base), toJson(ours), toJson(serverM)))
                } else {
                    // Fallback (no JSON view supplied): re-apply the mutation onto the winner.
                    mutate(serverM)
                }
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
