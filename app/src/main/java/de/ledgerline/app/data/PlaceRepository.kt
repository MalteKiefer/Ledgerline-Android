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

    /** Drop the encrypted place cache (called on forced logout / disconnect). */
    suspend fun clear() {
        context.placeCacheStore.edit { it.clear() }
    }
}

/** Map the reverse-geocode response to a [PhotoPlace]; null when nothing usable resolved. */
private fun ReverseResponse.toPhotoPlace(): PhotoPlace? {
    val display = place?.takeIf { it.isNotBlank() }
    val city = address["city"] ?: address["town"] ?: address["village"] ?: address["municipality"]
    val state = address["state"]
    val country = address["country"]
    if (display == null && city == null && state == null && country == null) return null
    return PhotoPlace(name = null, display = display, city = city, state = state, country = country)
}
