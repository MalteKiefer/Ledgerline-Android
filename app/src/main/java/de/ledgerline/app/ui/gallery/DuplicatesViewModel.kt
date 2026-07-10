package de.ledgerline.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.domain.gallery.DuplicateScanner
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.PhotoMetaBlob
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.MutateGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * On-device duplicate-photo scan orchestration, ported from the web `scanDuplicates`
 * flow. Ensures each candidate's meta blob is decrypted + cached (downloads on
 * [Dispatchers.IO]), builds [DuplicateScanner.DupItem]s, runs the O(n²) union-find on
 * [Dispatchers.Default], then maps id-groups back to [GalleryPhoto]s sorted by size
 * (largest first). Progress is surfaced by the shared overlay via
 * [OperationManager.active]; this VM holds no progress flow of its own.
 *
 * SECURITY: touches plaintext embeddings via [MetaCache]; those are wiped on lock.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val cache: GalleryCache,
    private val metaCache: MetaCache,
    private val blobs: GalleryBlobs,
    private val mutate: MutateGallery,
    private val operationManager: OperationManager,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _groups = MutableStateFlow<List<List<GalleryPhoto>>>(emptyList())
    val groups: StateFlow<List<List<GalleryPhoto>>> = _groups

    private val _scanned = MutableStateFlow(false)
    val scanned: StateFlow<Boolean> = _scanned

    private val _marked = MutableStateFlow<Set<String>>(emptySet())
    val marked: StateFlow<Set<String>> = _marked

    fun scan() {
        operationManager.run(OpKind.DUPLICATE_SCAN) { report ->
            val manifest = cache.value.value?.manifest ?: return@run
            val targets = manifest.photos.filter { !it.trashed }

            // 1. Ensure meta blobs are decrypted + cached (downloads on IO).
            withContext(Dispatchers.IO) {
                val toFetch = targets.filter { it.metaRef != null && !metaCache.has(it.id) }
                val total = toFetch.size
                var done = 0
                report(done, total)
                for (p in toFetch) {
                    val ref = p.metaRef ?: continue
                    when (val r = blobs.download(ref, p.metaKey ?: "")) {
                        is Outcome.Ok -> {
                            val meta = try {
                                json.decodeFromString<PhotoMetaBlob>(String(r.value))
                            } catch (_: Exception) {
                                null
                            }
                            metaCache.put(p.id, meta)
                        }
                        is Outcome.Err -> metaCache.put(p.id, null)
                    }
                    done++
                    report(done, total)
                }
            }

            // 2. Build DupItems from cached meta.
            val items = targets.map { p ->
                val meta = metaCache.get(p.id)
                DuplicateScanner.DupItem(
                    id = p.id,
                    embNorm = meta?.embedding?.takeIf { it.isNotEmpty() }
                        ?.let { DuplicateScanner.norm(it) },
                    phash = meta?.phash,
                    isVideo = p.media_type == "video",
                )
            }

            // 3. Union-find on Default (O(n²)).
            val idGroups = withContext(Dispatchers.Default) {
                DuplicateScanner.groups(items) { c, t -> report(c, t) }
            }

            // 4. Map ids back to non-trashed photos, sort each group by size desc.
            val byId = manifest.photos.filter { !it.trashed }.associateBy { it.id }
            val built = idGroups
                .map { g -> g.mapNotNull { byId[it] }.sortedByDescending { it.size ?: 0L } }
                .filter { it.size >= 2 }

            _groups.value = built
            _marked.value = emptySet()
            _scanned.value = true
        }
    }

    fun toggleMark(id: String) {
        _marked.value = _marked.value.let { if (id in it) it - id else it + id }
    }

    /** Mark every photo in [group] except the first (largest) — the copies. */
    fun markRest(group: List<GalleryPhoto>) {
        _marked.value = _marked.value + group.drop(1).map { it.id }
    }

    fun clearMarks() {
        _marked.value = emptySet()
    }

    /** Trash all marked photos, then drop them from the groups (dropping groups ≤ 1). */
    fun trashMarked() {
        val toTrash = _marked.value
        if (toTrash.isEmpty()) return
        viewModelScope.launch {
            mutate.invoke { m ->
                m.copy(photos = m.photos.map { if (it.id in toTrash) it.copy(trashed = true) else it })
            }
            _groups.value = recomputeAfterTrash(_groups.value, toTrash)
            _marked.value = emptySet()
        }
    }
}

/**
 * Remove [trashed] photo ids from each group and drop groups that fall to a single
 * (or no) remaining member. Pure — unit-tested.
 */
internal fun recomputeAfterTrash(
    groups: List<List<GalleryPhoto>>,
    trashed: Set<String>,
): List<List<GalleryPhoto>> =
    groups
        .map { g -> g.filterNot { it.id in trashed } }
        .filter { it.size > 1 }
