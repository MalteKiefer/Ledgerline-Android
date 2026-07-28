package de.ledgerline.app.core.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

/**
 * Downloads SRTM3 elevation tiles (`.hgt.zip`, 1°×1°, ~1 MB each) for the map viewport so mapsforge
 * can render **hillshading / terrain relief**. Tiles land in `filesDir/dem/`; mapsforge reads them
 * through a `DemFolderFS`. Third-party host → dedicated OkHttp client with a descriptive UA.
 */
@Singleton
class DemStore @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private companion object {
        const val BASE = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/dem/dem3/"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    val dir: File by lazy { File(context.filesDir, "dem").apply { mkdirs() } }
    fun demFolder(): File = dir

    /** Bumped whenever a new tile finishes downloading, so the map can re-render with shading. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun tileFile(latInt: Int, lonInt: Int): String {
        val la = if (latInt >= 0) "N%02d".format(Locale.US, latInt) else "S%02d".format(Locale.US, -latInt)
        val lo = if (lonInt >= 0) "E%03d".format(Locale.US, lonInt) else "W%03d".format(Locale.US, -lonInt)
        return "$la$lo.hgt.zip"
    }

    private fun bandOf(latInt: Int): String = if (latInt >= 0) "N%02d".format(Locale.US, latInt) else "S%02d".format(Locale.US, -latInt)

    /** Ensure the DEM tiles covering the given bounds are present (downloads missing ones). */
    fun ensureTilesFor(minLat: Double, minLng: Double, maxLat: Double, maxLng: Double) {
        val latLo = floor(minLat).toInt()
        val latHi = floor(maxLat).toInt()
        val lonLo = floor(minLng).toInt()
        val lonHi = floor(maxLng).toInt()
        // Guard against absurd spans (zoomed way out) → don't fetch the whole planet.
        if ((latHi - latLo) > 4 || (lonHi - lonLo) > 4) return
        for (la in latLo..latHi) for (lo in lonLo..lonHi) {
            val name = tileFile(la, lo)
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 0) continue
            if (!inFlight.add(name)) continue
            val url = BASE + bandOf(la) + "/" + name
            scope.launch(Dispatchers.IO) {
                try {
                    val part = File(dir, "$name.part")
                    http.newCall(Request.Builder().url(url).header("User-Agent", "de.ledgerline.app").build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { input -> part.outputStream().use { input.copyTo(it) } }
                            if (part.exists() && part.length() > 0 && part.renameTo(dest)) _version.value += 1
                        }
                    }
                    part.delete()
                } catch (_: Exception) {
                    File(dir, "$name.part").delete()
                } finally {
                    inFlight.remove(name)
                }
            }
        }
    }
}
