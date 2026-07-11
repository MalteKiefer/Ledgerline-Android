package de.ledgerline.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Forward-geocoding via OpenStreetMap Nominatim, mirroring the web `geoSearch`
 * (`resources/js/app.js`): a single public GET per submit, jumping a map to an
 * address. This is the same privacy class as the already-accepted osmdroid OSM
 * tiles. The call is isolated here so callers stay UI-only and it is testable.
 *
 * A proper `User-Agent` is required by the Nominatim usage policy; requests run on
 * [Dispatchers.IO] and never throw (any failure → null).
 */
class Geocoder(
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Returns the first match's `(lat, lng)` for the query, or null on empty/failure. */
    suspend fun search(q: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val query = q.trim()
        if (query.isBlank()) return@withContext null
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val arr = JSONArray(body)
                if (arr.length() == 0) return@withContext null
                val first = arr.getJSONObject(0)
                val lat = first.getString("lat").toDoubleOrNull() ?: return@withContext null
                val lng = first.getString("lon").toDoubleOrNull() ?: return@withContext null
                lat to lng
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val USER_AGENT = "Ledgerline-Android"
    }
}
