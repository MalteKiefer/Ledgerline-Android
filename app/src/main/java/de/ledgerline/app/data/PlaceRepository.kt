package de.ledgerline.app.data

import androidx.annotation.VisibleForTesting
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-proxied place search: forward-geocode (free text -> coordinate) and Maps-link
 * resolution, both used by Explore's map search + place lookups. Neither egress path talks to a
 * third party directly (ZK/metadata posture) — the server proxies the lookup.
 */
@Singleton
class PlaceRepository @VisibleForTesting internal constructor(
    private val sessionHolder: SessionHolder,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
    ) : this(sessionHolder, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    /**
     * Forward-geocode a free-text place query to `(lat, lng)` via the server proxy
     * (`GET /gallery/geocode`) — the query + client IP never touch a third party. Returns the
     * first candidate, or null on blank/failure. Not cached (a transient, one-off search).
     */
    suspend fun geocode(q: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val query = q.trim()
        if (query.isBlank()) return@withContext null
        val session = sessionHolder.get() ?: return@withContext null
        try {
            val r = apiProvider(session).galleryGeocode(query)
            if (!r.isSuccessful) return@withContext null
            r.body()?.results?.firstNotNullOfOrNull { hit ->
                val lat = hit.lat ?: return@firstNotNullOfOrNull null
                val lng = hit.lng ?: return@firstNotNullOfOrNull null
                lat to lng
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A place query OR a Google/Apple-Maps link → `(lat,lng)`. A maps URL is resolved server-side
     * (`GET /maps/resolve`, follows the short link opt-in); anything else is forward-geocoded. Keeps
     * both egress paths server-proxied (ZK). Null on blank/failure.
     */
    suspend fun searchOrResolve(input: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val q = input.trim()
        if (q.isBlank()) return@withContext null
        val session = sessionHolder.get() ?: return@withContext null
        if (looksLikeMapLink(q)) {
            val hit = runCatching {
                val r = apiProvider(session).mapsResolve(q)
                val b = if (r.isSuccessful) r.body() else null
                val lat = b?.lat; val lng = b?.lng
                if (lat != null && lng != null) lat to lng else null
            }.getOrNull()
            if (hit != null) return@withContext hit
            // A URL that didn't resolve → don't fall through to geocode (it isn't a place name).
            return@withContext null
        }
        geocode(q)
    }

    private fun looksLikeMapLink(s: String): Boolean =
        s.startsWith("http", ignoreCase = true) &&
            Regex("google\\.[a-z.]+/maps|maps\\.app\\.goo\\.gl|goo\\.gl/maps|maps\\.apple\\.com", RegexOption.IGNORE_CASE).containsMatchIn(s)

    /** No-op now that the (photo-place) encrypted cache is gone; kept so callers (forced
     *  logout/disconnect) don't need a special case. */
    suspend fun clear() {}
}
