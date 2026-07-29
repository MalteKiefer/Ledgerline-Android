package de.ledgerline.app.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ReverseResponse
import de.ledgerline.app.domain.model.PhotoPlace
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.placeCacheStore: DataStore<Preferences> by
    preferencesDataStore(name = "ledgerline_place_cache")

/**
 * Resolves a photo coordinate to a human place name via the server's ZK reverse-geocoder
 * (`GET /gallery/reverse` → self-hosted Photon, snap-to-grid, never cached server-side).
 *
 * The result is cached ON DEVICE but **encrypted** (sealed with the Vault Key), keyed by a
 * coarse ~111 m grid so nearby photos share one lookup and no plaintext location touches
 * disk. Without an unlocked vault the cache is unreadable — matching the app's ZK model.
 */
@Singleton
class PlaceRepository @VisibleForTesting internal constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    @ApplicationContext private val context: Context,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        @ApplicationContext context: Context,
    ) : this(sessionHolder, vaultKeyHolder, crypto, context, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    private val json = Json { ignoreUnknownKeys = true }

    /** ~111 m grid → many photos at one place reuse a single lookup + cache entry. */
    private fun coarseKey(lat: Double, lng: Double): String = "%.3f,%.3f".format(Locale.US, lat, lng)

    /** Resolve [lat]/[lng] to a place: encrypted cache first, then the server, else null. */
    suspend fun resolve(lat: Double, lng: Double): PhotoPlace? = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext null
        val cacheKey = stringPreferencesKey(coarseKey(lat, lng))
        context.placeCacheStore.data.first()[cacheKey]?.let { sealed ->
            crypto.openManifest(sealed, vk)?.let { plain ->
                runCatching { json.decodeFromString<PhotoPlace>(plain) }.getOrNull()?.let { return@withContext it }
            }
        }
        val session = sessionHolder.get() ?: return@withContext null
        val place = try {
            val r = apiProvider(session).galleryReverse(lat, lng)
            if (r.isSuccessful) r.body()?.toPhotoPlace() else null
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val sealed = crypto.sealManifest(json.encodeToString(PhotoPlace.serializer(), place), vk)
        context.placeCacheStore.edit { it[cacheKey] = sealed }
        place
    }

    /**
     * Forward-geocode a free-text place query to `(lat, lng)` via the server proxy
     * (`GET /gallery/geocode`) — the query + client IP never touch a third party, matching the
     * ZK/metadata posture the reverse path already honours. Returns the first candidate, or null on
     * blank/failure. Not cached (a transient search, not a photo's resolved place).
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

    /** Drop the encrypted place cache (called on forced logout / disconnect). */
    suspend fun clear() {
        context.placeCacheStore.edit { it.clear() }
    }
}

/** Map the reverse-geocode response to a [PhotoPlace]; null when nothing usable resolved. */
private fun ReverseResponse.toPhotoPlace(): PhotoPlace? {
    val display = place?.takeIf { it.isNotBlank() }
    // `address` is `{}` when populated but `[]` when empty (server quirk) → only read string parts
    // when it actually decoded to an object; otherwise fall back to just the display name.
    val parts = (address as? kotlinx.serialization.json.JsonObject).orEmpty()
    fun part(k: String) = (parts[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    val city = part("city") ?: part("town") ?: part("village") ?: part("municipality")
    val state = part("state")
    val country = part("country")
    if (display == null && city == null && state == null && country == null) return null
    return PhotoPlace(name = null, display = display, city = city, state = state, country = country)
}

private fun kotlinx.serialization.json.JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> =
    this ?: emptyMap()
