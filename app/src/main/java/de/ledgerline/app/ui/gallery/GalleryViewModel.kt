package de.ledgerline.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.domain.gallery.GalleryTrashOps
import de.ledgerline.app.domain.gallery.SemanticSearch
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.PhotoMetaBlob
import de.ledgerline.app.domain.model.PhotoPlace
import de.ledgerline.app.domain.usecase.EmbedText
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUsage
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.LoadGallery
import de.ledgerline.app.domain.usecase.PhotoSource
import de.ledgerline.app.ui.workspace.files.UsageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import javax.inject.Inject

data class GalleryUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val photos: List<GalleryPhoto> = emptyList(),
    /** The same [photos], grouped by capture day for the timeline grid. Presentation only. */
    val dayGroups: List<DayGroup> = emptyList(),
)

/** One capture-day bucket for the timeline grid: a stable day key, a display
 *  label, and the photos taken that day (newest-day-first, in-day order preserved). */
data class DayGroup(
    val dayKey: String,
    val label: String,
    val photos: List<GalleryPhoto>,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val load: LoadGallery,
    private val cache: GalleryCache,
    private val blobs: GalleryBlobs,
    private val thumbs: ThumbCache,
    private val galleryUsage: GalleryUsage,
    private val importPhotos: ImportPhotos,
    private val mutate: de.ledgerline.app.domain.usecase.MutateGallery,
    private val lockGuard: LockGuard,
    private val vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
    private val operationManager: OperationManager,
    private val embedText: EmbedText,
    private val metaCache: MetaCache,
    private val places: de.ledgerline.app.data.PlaceRepository,
    degradedState: de.ledgerline.app.core.offline.DegradedState,
) : ViewModel() {

    /** True when the gallery store is degraded (a shard blob is missing); writes are frozen. */
    val degraded: kotlinx.coroutines.flow.StateFlow<Boolean> = degradedState.gallery

    /** IO dispatcher for meta-blob downloads during search — overridable in tests so
     *  the work runs deterministically and doesn't leak a coroutine past the test. */
    @get:androidx.annotation.VisibleForTesting
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private val metaJson = Json { ignoreUnknownKeys = true }

    private val placeCache = mutableMapOf<String, PhotoPlace?>()
    private val _state = MutableStateFlow(GalleryUi(loading = true))
    val state: StateFlow<GalleryUi> = _state

    private val _usage = MutableStateFlow<UsageInfo?>(null)
    val usage: StateFlow<UsageInfo?> = _usage

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** True while the full-screen trash view is showing (grid = only trashed photos). */
    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash

    /** Count of trashed photos (drives the overflow "Trash (N)" entry). */
    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    fun setTrash(show: Boolean) {
        _showTrash.value = show
        recompute()
    }

    fun toggleTrash() = setTrash(!_showTrash.value)

    /** When true the Photos grid shows only favorited photos. */
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
        recompute()
    }

    /** Non-destructive rotate: cycle rotation 0→90→180→270→0 on the photo entry. */
    fun rotatePhoto(id: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id == id) it.copy(rotation = (it.rotation + 90) % 360) else it }) }
    }

    /** Non-destructive horizontal flip toggle on the photo entry. */
    fun flipHorizontal(id: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id == id) it.copy(flipH = !it.flipH) else it }) }
    }

    /** Non-destructive vertical flip toggle on the photo entry. */
    fun flipVertical(id: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id == id) it.copy(flipV = !it.flipV) else it }) }
    }

    /** Toggle the favorite flag on a single photo. */
    fun toggleFavorite(id: String) = viewModelScope.launch {
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }) }
    }

    /** Bulk-set the favorite flag on the given photos (selection toolbar). */
    fun setFavorite(ids: Set<String>, favorite: Boolean) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id in ids) it.copy(favorite = favorite) else it }) }
    }

    /** Bulk-set the capture date (`taken_at`, ISO instant) on the given photos.
     *  Single-photo callers pass `setOf(id)`. Matches the web `bulkApplyDate`. */
    fun setDate(ids: Set<String>, iso: String) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id in ids) it.copy(taken_at = iso) else it }) }
    }

    /** Bulk-set the location (`lat`/`lng`) on the given photos. Single-photo callers
     *  pass `setOf(id)`. Matches the web location picker (single + bulk). */
    fun setLocation(ids: Set<String>, lat: Double, lng: Double) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id in ids) it.copy(lat = lat, lng = lng) else it }) }
    }

    fun clearMessage() { _message.value = null }

    // --- Semantic search (CLIP embed-text + cosine over cached embeddings) ---
    //
    // Mirrors the web `_doSearch` (resources/js/app.js): embed the query text, cosine
    // it against each non-trashed photo's normalised CLIP embedding (kept `> 0.2`,
    // ranked desc, capped), then supplement with a case-insensitive metadata substring
    // match (name/camera on the index, place from the decrypted meta) — content hits
    // first, deduped. A lightweight `viewModelScope.launch` (NOT the foreground-service
    // OperationManager) drives it; `searching` gates a spinner.

    /** null = no active query (normal grouped grid). Empty list = searched, no matches. */
    private val _searchResults = MutableStateFlow<List<GalleryPhoto>?>(null)
    val searchResults: StateFlow<List<GalleryPhoto>?> = _searchResults

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    /** Meta fetched / total for the loading label; 0/0 when idle. */
    private val _searchProgress = MutableStateFlow(0 to 0)
    val searchProgress: StateFlow<Pair<Int, Int>> = _searchProgress

    private var searchJob: kotlinx.coroutines.Job? = null

    fun clearSearch() {
        searchJob?.cancel()
        _searching.value = false
        _searchProgress.value = 0 to 0
        _searchResults.value = null
    }

    /**
     * Run a semantic + metadata search over the non-trashed photos. Cancels any prior
     * search. Blank query clears. Embeds the query, ensures meta (embeddings/place) is
     * decrypted+cached (only for photos not already in [MetaCache]), ranks by cosine,
     * then appends metadata substring matches, deduped.
     */
    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isBlank()) { clearSearch(); return }
        searchJob = viewModelScope.launch {
            _searching.value = true
            val lc = q.lowercase()
            val targets = cache.value.value?.manifest?.photos.orEmpty().filter { !it.trashed }

            // Ensure decrypted meta for every candidate (skip those already cached).
            withContext(ioDispatcher) {
                val toFetch = targets.filter { it.metaRef != null && !metaCache.has(it.id) }
                _searchProgress.value = 0 to toFetch.size
                var done = 0
                for (p in toFetch) {
                    val ref = p.metaRef ?: continue
                    when (val r = blobs.download(ref, p.metaKey ?: "")) {
                        is Outcome.Ok -> metaCache.put(
                            p.id,
                            try { metaJson.decodeFromString<PhotoMetaBlob>(String(r.value)) } catch (_: Exception) { null },
                        )
                        is Outcome.Err -> metaCache.put(p.id, null)
                    }
                    _searchProgress.value = ++done to toFetch.size
                }
            }

            // CLIP content matches: embed text, cosine vs cached normalised embeddings — but
            // only embeddings sharing the library's CURRENT model (web `embModel === clipModel`),
            // so a stale-model embedding can't pollute results. The query is embedded by the
            // current server model, so only same-model photo embeddings are comparable.
            val current = SemanticSearch.currentModel(targets.map { metaCache.get(it.id)?.embModel })
            val contentIds = embedText.invoke(q)?.let { qv ->
                val qn = SemanticSearch.norm(qv)
                val items = targets.map { p ->
                    val meta = metaCache.get(p.id)
                    val emb = meta?.embedding
                        ?.takeIf { it.isNotEmpty() && (current == null || meta.embModel == current) }
                        ?.let { SemanticSearch.norm(it) }
                    p.id to emb
                }
                SemanticSearch.rank(qn, items, threshold = SEARCH_THRESHOLD)
            }.orEmpty()

            // Metadata supplement (case-insensitive substring on name/camera/place).
            val metaIds = targets.filter { p ->
                (p.name?.lowercase()?.contains(lc) == true) ||
                    (p.camera?.lowercase()?.contains(lc) == true) ||
                    (metaCache.get(p.id)?.place?.let { pl ->
                        listOfNotNull(pl.name, pl.display, pl.city, pl.state, pl.country)
                            .any { it.lowercase().contains(lc) }
                    } == true)
            }.map { it.id }

            val byId = targets.associateBy { it.id }
            val order = (contentIds + metaIds).distinct()
            _searchResults.value = order.mapNotNull { byId[it] }
            _searching.value = false
            _searchProgress.value = 0 to 0
        }
    }

    /** Soft-delete (trash) the selected photos — sets `trashed=true` in the gallery
     *  index (recoverable, matching the web). The read side filters trashed out. */
    fun trashPhotos(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        mutate.invoke { m -> m.copy(photos = m.photos.map { if (it.id in ids) it.copy(trashed = true) else it }) }
    }

    /** Restore the given photos from the trash (clears `trashed`). */
    fun restorePhotos(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        mutate.invoke { m -> GalleryTrashOps.restore(m, ids) }
    }

    /**
     * Permanently delete the given photos: collect their freed blob refs, drop the
     * photos from the manifest (also cleaning dangling album/person references via
     * [GalleryTrashOps.remove]), then release the freed blobs (429-aware). Usage is
     * refreshed once the deletes have landed.
     */
    fun deleteForever(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val freed = cache.value.value?.manifest?.let { GalleryTrashOps.freedRefs(it, ids) }.orEmpty()
        val res = mutate.invoke { m -> GalleryTrashOps.remove(m, ids) }
        if (res is Outcome.Ok) {
            blobs.deleteBlobs(freed)
            loadUsage()
        }
    }

    /** Permanently delete ALL trashed photos, freeing their blobs and cleaning refs. */
    fun emptyTrash() = viewModelScope.launch {
        val current = cache.value.value?.manifest ?: return@launch
        val trashedIds = current.photos.filter { it.trashed }.map { it.id }.toSet()
        if (trashedIds.isEmpty()) return@launch
        val freed = GalleryTrashOps.freedRefs(current, trashedIds)
        val res = mutate.invoke { m -> GalleryTrashOps.emptyTrash(m) }
        if (res is Outcome.Ok) {
            blobs.deleteBlobs(freed)
            loadUsage()
        }
    }

    init {
        viewModelScope.launch {
            cache.value.collect { g -> if (g != null) recompute() else _state.value = GalleryUi(loading = true) }
        }
        // This ViewModel is activity-scoped and survives a lock (which wipes the
        // gallery cache). Re-fetch whenever the vault is unlocked and the cache is
        // empty — covers both first open and returning from the lock screen, where
        // init() no longer re-runs. Emits immediately with the current unlock state.
        viewModelScope.launch {
            vaultKeyHolder.unlocked.collect { unlocked ->
                if (unlocked && cache.value.value == null) refresh()
            }
        }
        loadUsage()
    }

    /** Fetch gallery blob usage (used/quota) and publish it; silently ignores failure. */
    fun loadUsage() = viewModelScope.launch {
        galleryUsage.invoke()?.let { (used, quota) -> _usage.value = UsageInfo(used, quota) }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        // Clear the spinner explicitly on success too: GalleryCache holds a data-class
        // Gallery, so a reload of unchanged data is value-equal and the StateFlow does NOT
        // re-emit — the cache collector would never fire and `loading` would stick forever.
        when (load.invoke()) {
            is Outcome.Err -> _state.value = _state.value.copy(loading = false, error = true)
            is Outcome.Ok -> recompute()
        }
        loadUsage()
    }

    /** Arm the lock guard so the system picker launch does not trigger idle-lock. */
    fun armLockSuppression() = lockGuard.armSkipOnce()

    /**
     * Upload each source serially: read bytes, compute sha-256 sig, dedup against
     * already-known sigs, upload, and append the new entry to the gallery index.
     * The work runs through [OperationManager] so it survives backgrounding and its
     * progress feeds the shared overlay + service notification; failures are counted
     * and surfaced as `"upload_failed:N"` in [message]. [loadUsage] is called when the
     * queue drains.
     */
    // Sources that failed in the last import, retryable within the session (their content
    // Uris are still readable). Not persisted — an app restart drops them.
    private var lastFailedImports: List<PhotoSource> = emptyList()
    private val _failedImportCount = MutableStateFlow(0)
    val failedImportCount: StateFlow<Int> = _failedImportCount

    fun uploadAll(sources: List<PhotoSource>) {
        operationManager.run(OpKind.UPLOAD, total = sources.size) { report ->
            val result = importPhotos.invoke(sources, report)
            loadUsage()
            lastFailedImports = result.failedSources
            _failedImportCount.value = result.failedSources.size
            if (result.failed > 0) _message.value = "upload_failed:${result.failed}"
        }
    }

    // --- Deferred CLIP re-index (analyze backfill) ---
    private val _reindexProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** null = idle; (done,total) while a search re-index runs. Drives the jobs-sheet progress. */
    val reindexProgress: StateFlow<Pair<Int, Int>?> = _reindexProgress

    /**
     * Web `reindexAll`/`_reembedOne`: re-embed every non-trashed photo whose sealed CLIP embedding
     * is missing or on a stale model, so it becomes searchable. Decrypts the medium rendition
     * transiently, POSTs it to `/gallery/analyze`, and reseals the meta (embedding + embModel only —
     * faces untouched). The gallery store is updated ONCE at the end; superseded meta blobs are
     * reclaimed by reconcile-on-load. Best-effort — a failed photo is picked up on a later run.
     */
    fun reindexSearch() {
        if (_reindexProgress.value != null) return
        viewModelScope.launch {
            val all = cache.value.value?.manifest?.photos.orEmpty()
                .filter { !it.trashed && it.metaRef != null && (it.mediumRef != null || it.originalRef != null) }
            withContext(ioDispatcher) {
                for (p in all.filter { !metaCache.has(it.id) }) {
                    val ref = p.metaRef ?: continue
                    when (val r = blobs.download(ref, p.metaKey ?: "")) {
                        is Outcome.Ok -> metaCache.put(
                            p.id,
                            try { metaJson.decodeFromString<PhotoMetaBlob>(String(r.value)) } catch (_: Exception) { null },
                        )
                        is Outcome.Err -> metaCache.put(p.id, null)
                    }
                }
            }
            val current = SemanticSearch.currentModel(all.map { metaCache.get(it.id)?.embModel })
            // Missing embedding (never analysed) OR a tagged embedding on a non-current model.
            val todo = all.filter { p ->
                val m = metaCache.get(p.id) ?: return@filter false
                m.embedding.isEmpty() || (current != null && m.embModel != null && m.embModel != current)
            }
            if (todo.isEmpty()) { _message.value = "reindex_none"; return@launch }
            _reindexProgress.value = 0 to todo.size
            val updates = HashMap<String, Pair<String, String>>()
            var done = 0
            for (p in todo) {
                val ref = p.mediumRef ?: p.originalRef!!
                val key = (if (p.mediumRef != null) p.mediumKey else p.originalKey) ?: ""
                blobs.reembed(ref, key, p.metaRef!!, p.metaKey ?: "")?.let { updates[p.id] = it.metaRef to it.metaKey }
                _reindexProgress.value = ++done to todo.size
            }
            if (updates.isNotEmpty()) {
                mutate.invoke { m ->
                    m.copy(photos = m.photos.map { ph -> updates[ph.id]?.let { ph.copy(metaRef = it.first, metaKey = it.second) } ?: ph })
                }
                metaCache.clear() // resealed metas → drop stale decrypts so search re-reads the new embedding/embModel
            }
            _reindexProgress.value = null
            _message.value = "reindex_done:${updates.size}"
        }
    }

    /** Re-run the import for the sources that failed in the last upload batch. */
    fun retryFailedImports() {
        val retry = lastFailedImports
        lastFailedImports = emptyList()
        _failedImportCount.value = 0
        if (retry.isNotEmpty()) uploadAll(retry)
    }

    /** Returns a cached thumbnail bitmap or downloads+decodes it (cached). Null on failure. */
    suspend fun thumb(photo: GalleryPhoto): Bitmap? {
        thumbs.get(photo.id)?.let { return it }
        val ref = photo.thumbRef ?: return null
        val key = photo.thumbKey ?: return null
        return when (val r = blobs.download(ref, key)) {
            // Decode off the caller's dispatcher (produceState composes on Main) and as
            // RGB_565 — photo thumbnails need no alpha, so this halves the bitmap memory and
            // lets a larger in-memory cache fit without OOM risk.
            is Outcome.Ok -> withContext(ioDispatcher) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeByteArray(r.value, 0, r.value.size, opts)
            }?.also { thumbs.put(photo.id, it) }
            is Outcome.Err -> null
        }
    }

    suspend fun downloadBytes(ref: String, key: String): Outcome<ByteArray> = blobs.download(ref, key)

    /** Decrypt a photo's ORIGINAL blob fully into memory (for export via SAF). Null on failure. */
    suspend fun originalBytes(photo: GalleryPhoto): ByteArray? {
        val ref = photo.originalRef ?: return null
        val key = photo.originalKey ?: return null
        return (blobs.download(ref, key) as? Outcome.Ok)?.value
    }

    /**
     * Resolve a photo's place for the viewer. First the sealed meta blob (populated at
     * upload only when the server has geocode-on-upload enabled); when that has no place,
     * fall back to an on-demand reverse-geocode of the coordinate (ZK: server-proxied
     * self-hosted geocoder + an encrypted, coarse-grid on-device cache). Cached per photo
     * id in memory. Returns null on any failure / no coordinate.
     */
    suspend fun loadPlace(photo: GalleryPhoto): PhotoPlace? {
        if (placeCache.containsKey(photo.id)) return placeCache[photo.id]
        val fromMeta = photo.metaRef?.let { ref ->
            photo.metaKey?.let { key ->
                try {
                    when (val r = blobs.download(ref, key)) {
                        is Outcome.Ok ->
                            Json { ignoreUnknownKeys = true }.decodeFromString<PhotoMetaBlob>(String(r.value)).place
                        is Outcome.Err -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
        val place = fromMeta ?: run {
            val lat = photo.lat
            val lng = photo.lng
            if (lat != null && lng != null) places.resolve(lat, lng) else null
        }
        placeCache[photo.id] = place
        return place
    }

    /** Place query OR maps link → coordinates, all server-proxied (never third-party-direct). */
    suspend fun geocode(query: String): Pair<Double, Double>? = places.searchOrResolve(query)

    /** Library counts for the Jobs/diagnostics sheet (non-trashed). */
    fun diagnostics(): Triple<Int, Int, Int> {
        val all = cache.value.value?.manifest?.photos.orEmpty().filter { !it.trashed }
        return Triple(
            all.count { it.media_type != "video" },
            all.count { it.media_type == "video" },
            all.count { it.lat != null && it.lng != null },
        )
    }

    fun photoById(id: String) = cache.value.value?.manifest?.photos?.firstOrNull { it.id == id }

    /** All non-trashed photos that carry a geotag (both [GalleryPhoto.lat] and
     *  [GalleryPhoto.lng] set) — the set rendered on the full-gallery map. */
    fun geotaggedPhotos(): List<GalleryPhoto> =
        cache.value.value?.manifest?.photos.orEmpty()
            .filter { !it.trashed && it.lat != null && it.lng != null }

    // Cached (sourceList identity → result) so the backfill decrypt pass runs once per manifest.
    private var geoCache: Pair<List<GalleryPhoto>, List<GalleryPhoto>>? = null

    /**
     * Map set with a lazy geo-backfill: photos with a record geotag, PLUS older photos that
     * lack one but carry lat/lon in their sealed meta blob's exif (decrypted + read on demand;
     * cheap on repeat opens thanks to the cache-first blob cache). Result is memoised per
     * manifest instance so the decrypt pass runs once.
     */
    suspend fun geotaggedWithBackfill(): List<GalleryPhoto> {
        val all = cache.value.value?.manifest?.photos.orEmpty()
        geoCache?.let { if (it.first === all) return it.second }
        val result = withContext(ioDispatcher) {
            val live = all.filter { !it.trashed }
            val (has, missing) = live.partition { it.lat != null && it.lng != null }
            val candidates = missing.filter { it.metaRef != null && it.metaKey != null }
            val json = Json { ignoreUnknownKeys = true }
            val sem = Semaphore(8)
            val backfilled = coroutineScope {
                candidates.map { p ->
                    async {
                        sem.withPermit {
                            runCatching {
                                val r = blobs.download(p.metaRef!!, p.metaKey!!)
                                val ex = (r as? Outcome.Ok)
                                    ?.let { json.decodeFromString<PhotoMetaBlob>(String(it.value)) }?.exif
                                val lat = (ex?.get("lat") as? JsonPrimitive)?.doubleOrNull
                                val lng = (ex?.get("lon") as? JsonPrimitive)?.doubleOrNull
                                if (lat != null && lng != null) p.copy(lat = lat, lng = lng) else null
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            has + backfilled
        }
        geoCache = all to result
        return result
    }

    private fun recompute() {
        // Sort by capture date (EXIF taken_at), falling back to upload time — matches
        // the web timeline (newest first). Parse to an epoch so mixed ISO / EXIF
        // (`2026:07:11 ...`) formats compare correctly instead of clustering by the
        // just-uploaded order.
        val all = cache.value.value?.manifest?.photos.orEmpty()
        _trashCount.value = all.count { it.trashed }
        // Normal grid = untrashed; trash view = only trashed. Both newest-first.
        // The favorites filter only applies to the normal (non-trash) grid.
        val photos = all
            .filter { it.trashed == _showTrash.value }
            .filter { !_favoritesOnly.value || _showTrash.value || it.favorite }
            .sortedByDescending { epochOf(it.taken_at ?: it.created) }
        _state.value = GalleryUi(false, false, photos, groupByDay(photos))
    }

    /** Best-effort epoch millis from an ISO-8601 or EXIF (`yyyy:MM:dd HH:mm:ss`)
     *  timestamp; 0 when null/unparseable (sorts oldest). */
    private fun epochOf(ts: String?): Long {
        if (ts.isNullOrBlank()) return 0L
        runCatching { return java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli() }
        runCatching { return java.time.Instant.parse(ts).toEpochMilli() }
        // EXIF "yyyy:MM:dd HH:mm:ss" → normalise the date part to ISO and retry as local.
        runCatching {
            val norm = ts.trim().replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"), "$1-$2-$3").replace(' ', 'T')
            return java.time.LocalDateTime.parse(norm)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return 0L
    }

    companion object {
        /**
         * Cosine cutoff for CLIP text↔image content matches. Kept strictly greater than
         * this, matching the web `_doSearch` (`resources/js/app.js`): `s > 0.2`.
         */
        const val SEARCH_THRESHOLD = 0.2

        /**
         * Group an already-sorted, already-filtered photo list by capture DAY
         * (the day part of `taken_at ?: created`) for the timeline grid — matching
         * the web. Input order is preserved: the first photo of each day fixes the
         * day's position (so a descending-by-date input yields newest-day-first
         * groups), and within a day photos keep their input order. Photos with a
         * blank/unparseable date land in the "unknown" group (label "—"), which — as
         * the last entries of a newest-first list (epoch 0) — sorts last.
         */
        fun groupByDay(photos: List<GalleryPhoto>): List<DayGroup> {
            val buckets = LinkedHashMap<String, MutableList<GalleryPhoto>>()
            for (p in photos) {
                val key = dayKeyOf(p.taken_at ?: p.created)
                buckets.getOrPut(key) { mutableListOf() }.add(p)
            }
            return buckets.map { (key, ps) -> DayGroup(key, dayLabel(key), ps) }
        }

        /** First 10 chars of an ISO/EXIF date (`yyyy-MM-dd`), or "unknown" if blank/short. */
        private fun dayKeyOf(ts: String?): String {
            if (ts.isNullOrBlank()) return "unknown"
            // Normalise EXIF `yyyy:MM:dd` to `yyyy-MM-dd`, then take the date part.
            val norm = ts.trim().replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"), "$1-$2-$3")
            val date = norm.take(10)
            return if (date.length == 10) date else "unknown"
        }

        /** Localized long date from a `yyyy-MM-dd` key; "unknown" → "—", else the raw key on failure. */
        private fun dayLabel(dayKey: String): String {
            if (dayKey == "unknown") return "—"
            return runCatching {
                java.time.LocalDate.parse(dayKey)
                    .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG))
            }.getOrDefault(dayKey)
        }
    }
}
