package de.ledgerline.app.core.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Download state for one offline-map region. */
sealed interface MapDownloadState {
    data object NotInstalled : MapDownloadState
    data class Downloading(val receivedBytes: Long, val totalBytes: Long, val bytesPerSec: Long) : MapDownloadState
    data class Installed(val bytes: Long) : MapDownloadState
    data class Failed(val reason: String) : MapDownloadState
}

/**
 * Manages downloaded mapsforge `.map` files: the region catalog (bundled asset), per-region
 * download with progress/speed, cancel, delete, and the set of installed maps handed to the
 * renderer. Third-party host, so a dedicated OkHttp client with a descriptive User-Agent —
 * never the app's pinned API client, no telemetry.
 */
@Singleton
class OfflineMapStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // large files stream for a long time
        .build()

    private val dir: File by lazy { File(context.filesDir, "maps").apply { mkdirs() } }
    private val jobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow<Map<String, MapDownloadState>>(emptyMap())
    /** Per-region download state, keyed by region id. */
    val state: StateFlow<Map<String, MapDownloadState>> = _state

    private val _updates = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of installed regions with a newer version available on the server. */
    val updates: StateFlow<Set<String>> = _updates

    private fun versionsFile() = File(dir, "versions.json")
    private fun loadVersions(): MutableMap<String, String> = runCatching {
        json.decodeFromString<Map<String, String>>(versionsFile().readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())
    private fun saveVersion(id: String, lastModified: String) {
        val m = loadVersions(); m[id] = lastModified
        runCatching { versionsFile().writeText(json.encodeToString(m as Map<String, String>)) }
    }

    val catalog: OfflineMapCatalog by lazy {
        context.assets.open("map-regions.json").use { json.decodeFromString(OfflineMapCatalog.serializer(), it.readBytes().decodeToString()) }
    }

    private fun fileFor(id: String) = File(dir, "$id.map")

    /** Rescan installed files → seed the state map. Call on init / when opening settings. */
    fun refreshInstalled() {
        val installed = catalog.leaves().associate { r ->
            val f = fileFor(r.id)
            r.id to if (f.exists() && f.length() > 0) MapDownloadState.Installed(f.length())
            else (_state.value[r.id] ?: MapDownloadState.NotInstalled)
        }
        _state.value = installed
    }

    /** Every installed `.map` file, for the renderer's [MultiMapDataStore]. */
    fun installedFiles(): List<File> =
        catalog.leaves().mapNotNull { fileFor(it.id).takeIf { f -> f.exists() && f.length() > 0 } }

    /** Total bytes on disk across all installed regions. */
    fun totalInstalledBytes(): Long = installedFiles().sumOf { it.length() }

    /** True if the region [id]'s `.map` file is present. */
    fun isInstalled(id: String): Boolean = fileFor(id).let { it.exists() && it.length() > 0 }

    /** The catalog leaf with this [id], if any. */
    fun region(id: String): OfflineMapRegion? = catalog.leaves().firstOrNull { it.id == id }

    /** The id of the bundled world overview map (a tiny always-available base layer). */
    val worldMapId: String get() = "world-map"

    fun startDownload(region: OfflineMapRegion) {
        val path = region.path ?: return
        if (jobs[region.id]?.isActive == true) return
        jobs[region.id] = scope.launch(Dispatchers.IO) {
            try {
                download(region.id, catalog.baseUrl.trimEnd('/') + "/" + path)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Keep the partial .part file so a later re-download resumes instead of restarting.
                setState(region.id, MapDownloadState.NotInstalled)
            } catch (e: Exception) {
                setState(region.id, MapDownloadState.Failed(e.message ?: "download failed"))
            } finally {
                jobs.remove(region.id)
            }
        }
    }

    fun cancelDownload(id: String) { jobs.remove(id)?.cancel() }

    fun delete(id: String) {
        cancelDownload(id)
        fileFor(id).delete()
        File(dir, "$id.map.part").delete()
        setState(id, MapDownloadState.NotInstalled)
    }

    private suspend fun download(id: String, url: String) {
        val part = File(dir, "$id.map.part")
        // Resume from the partial file if present (HTTP Range).
        val existing = if (part.exists()) part.length() else 0L
        setState(id, MapDownloadState.Downloading(existing, -1, 0))
        val reqB = Request.Builder().url(url).header("User-Agent", "de.ledgerline.app")
        if (existing > 0) reqB.header("Range", "bytes=$existing-")
        val resp = http.newCall(reqB.build()).execute()
        resp.use {
            // 416 = range past EOF → the partial file is already the complete file.
            if (it.code == 416 && existing > 0) { finalize(part, id); return }
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            val lastModified = it.header("Last-Modified")
            val body = it.body ?: throw java.io.IOException("empty body")
            // 206 Partial Content → append; anything else (200) means the server ignored the
            // Range header, so restart from scratch to avoid a corrupt file.
            val resuming = it.code == 206 && existing > 0
            if (!resuming) part.delete()
            var received = if (resuming) existing else 0L
            val total = if (resuming) existing + body.contentLength() else body.contentLength()
            var windowBytes = 0L
            var windowStart = System.nanoTime()
            var bps = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resuming).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        received += n
                        windowBytes += n
                        val elapsed = System.nanoTime() - windowStart
                        if (elapsed >= 500_000_000L) { // update speed ~2×/s
                            bps = (windowBytes * 1_000_000_000L / elapsed)
                            windowBytes = 0; windowStart = System.nanoTime()
                            setState(id, MapDownloadState.Downloading(received, total, bps))
                        }
                    }
                }
            }
            finalize(part, id)
            lastModified?.let { saveVersion(id, it) }
            _updates.update { it - id }
        }
    }

    /** Atomically publish the completed part file as the region's `.map`. */
    private fun finalize(part: File, id: String) {
        val dest = fileFor(id)
        dest.delete()
        if (!part.renameTo(dest)) throw java.io.IOException("finalize failed")
        setState(id, MapDownloadState.Installed(dest.length()))
    }

    /**
     * Check installed regions for a newer version on the server (HEAD → Last-Modified vs the value
     * captured at download). Populates [updates]. Cheap enough to run on screen open + periodically.
     */
    suspend fun checkUpdates() = withContext(Dispatchers.IO) {
        val vers = loadVersions()
        val found = mutableSetOf<String>()
        for (r in catalog.leaves()) {
            val path = r.path ?: continue
            if (!isInstalled(r.id)) continue
            val stored = vers[r.id] ?: continue
            val url = catalog.baseUrl.trimEnd('/') + "/" + path
            val serverLm = runCatching {
                http.newCall(Request.Builder().url(url).head().header("User-Agent", "de.ledgerline.app").build())
                    .execute().use { it.header("Last-Modified") }
            }.getOrNull()
            if (serverLm != null && serverLm != stored) found.add(r.id)
        }
        _updates.value = found
    }

    private fun setState(id: String, s: MapDownloadState) = _state.update { it + (id to s) }
}
