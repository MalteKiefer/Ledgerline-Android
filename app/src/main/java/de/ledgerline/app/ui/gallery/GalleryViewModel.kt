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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
) : ViewModel() {

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

            // CLIP content matches: embed text, cosine vs cached normalised embeddings.
            val contentIds = embedText.invoke(q)?.let { qv ->
                val qn = SemanticSearch.norm(qv)
                val items = targets.map { p ->
                    p.id to metaCache.get(p.id)?.embedding?.takeIf { it.isNotEmpty() }
                        ?.let { SemanticSearch.norm(it) }
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
        if (load.invoke() is Outcome.Err) _state.value = _state.value.copy(loading = false, error = true)
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
    fun uploadAll(sources: List<PhotoSource>) {
        operationManager.run(OpKind.UPLOAD, total = sources.size) { report ->
            val result = importPhotos.invoke(sources, report)
            loadUsage()
            if (result.failed > 0) _message.value = "upload_failed:${result.failed}"
        }
    }

    /** Returns a cached thumbnail bitmap or downloads+decodes it (cached). Null on failure. */
    suspend fun thumb(photo: GalleryPhoto): Bitmap? {
        thumbs.get(photo.id)?.let { return it }
        val ref = photo.thumbRef ?: return null
        val key = photo.thumbKey ?: return null
        return when (val r = blobs.download(ref, key)) {
            is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size)?.also { thumbs.put(photo.id, it) }
            is Outcome.Err -> null
        }
    }

    suspend fun downloadBytes(ref: String, key: String): Outcome<ByteArray> = blobs.download(ref, key)

    /** Lazily loads and decodes the encrypted meta blob's place. Cached per photo id. Returns null on any failure. */
    suspend fun loadPlace(photo: GalleryPhoto): PhotoPlace? {
        if (placeCache.containsKey(photo.id)) return placeCache[photo.id]
        val ref = photo.metaRef ?: return null
        val key = photo.metaKey ?: return null
        val place = try {
            when (val r = blobs.download(ref, key)) {
                is Outcome.Ok -> {
                    val metaJson = Json { ignoreUnknownKeys = true }
                    metaJson.decodeFromString<PhotoMetaBlob>(String(r.value)).place
                }
                is Outcome.Err -> null
            }
        } catch (_: Exception) {
            null
        }
        placeCache[photo.id] = place
        return place
    }

    fun photoById(id: String) = cache.value.value?.manifest?.photos?.firstOrNull { it.id == id }

    /** All non-trashed photos that carry a geotag (both [GalleryPhoto.lat] and
     *  [GalleryPhoto.lng] set) — the set rendered on the full-gallery map. */
    fun geotaggedPhotos(): List<GalleryPhoto> =
        cache.value.value?.manifest?.photos.orEmpty()
            .filter { !it.trashed && it.lat != null && it.lng != null }

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
