package de.ledgerline.app.core.offline

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.data.FileBlobRepository
import de.ledgerline.app.data.GalleryBlobRepository
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-downloads referenced blob **ciphertext** into the offline cache per the chosen
 * policy, running in-app via the [OperationManager] (`OpKind.PREFETCH`) while unlocked.
 *
 * Runtime frame: prefetch needs the in-memory session token and the ref list from the
 * *decrypted* manifest (VK-gated). Neither is persisted to a WorkManager job — that would
 * write secrets to disk. Downloads fetch ciphertext only (no VK), so a lock mid-run just
 * ends the run without corrupting anything. The ref list is snapshotted at start.
 */
@Singleton
class Prefetcher @Inject constructor(
    private val galleryCache: GalleryCache,
    private val workspaceCache: WorkspaceCache,
    private val galleryRepo: GalleryBlobRepository,
    private val fileRepo: FileBlobRepository,
    private val blobCache: BlobDiskCache,
    private val offlineFlags: OfflineFlags,
    private val constraints: Constraints,
    private val operationManager: OperationManager,
) {
    /** A blob ref plus which store it belongs to (routes to the right repo). */
    private data class Ref(val id: String, val isGallery: Boolean)

    private val _message = MutableStateFlow<String?>(null)

    /** Surfaces a manual-path reason (currently `"constraints"`) for the UI to show. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** Manual "Prefetch now": runs whenever any policy caches something; honours constraints. */
    fun prefetchNow() = run(auto = false)

    /** Auto on unlock: only runs when a prefetch policy (ALL / THUMBS) is active. */
    fun maybePrefetchOnUnlock() = run(auto = true)

    private fun run(auto: Boolean) {
        if (!offlineFlags.enabled()) return

        val photosPolicy = offlineFlags.photosPolicy()
        val filesPolicy = offlineFlags.filesPolicy()

        val photosIsPrefetch = photosPolicy == PhotoBlobPolicy.THUMBS || photosPolicy == PhotoBlobPolicy.ALL
        val filesIsPrefetch = filesPolicy == FileBlobPolicy.ALL

        // Nothing caches anything → no-op on both paths (auto and manual).
        if (!photosIsPrefetch && !filesIsPrefetch) return

        // Don't stack: a PREFETCH op is already running.
        if (operationManager.active.value.any { it.kind == OpKind.PREFETCH }) return

        // Constraints: manual surfaces a message, auto stays silent.
        if (!constraints.wifiConstraintMet(offlineFlags.wifiOnly()) ||
            !constraints.chargingConstraintMet(offlineFlags.chargingOnly())
        ) {
            if (!auto) _message.value = "constraints"
            return
        }

        val refs = ArrayList<Ref>()

        if (photosIsPrefetch) {
            val photos = galleryCache.value.value?.manifest?.photos.orEmpty().filter { !it.trashed }
            for (p in photos) {
                when (photosPolicy) {
                    PhotoBlobPolicy.THUMBS -> listOfNotNull(p.thumbRef)
                    PhotoBlobPolicy.ALL -> listOfNotNull(
                        p.thumbRef, p.mediumRef, p.originalRef, p.motionRef, p.metaRef,
                    ) + p.faceCropRefs
                    else -> emptyList()
                }.forEach { refs.add(Ref(it, isGallery = true)) }
            }
        }

        if (filesIsPrefetch) {
            val files = workspaceCache.value.value?.manifest?.files.orEmpty().filter { !it.trashed }
            for (f in files) {
                if (f.blob.isNotEmpty()) refs.add(Ref(f.blob, isGallery = false))
            }
        }

        // Dedup, drop already-cached.
        val pending = refs.distinctBy { it.id }.filterNot { blobCache.has(it.id) }
        if (pending.isEmpty()) return

        operationManager.run(OpKind.PREFETCH, total = pending.size) { report ->
            var done = 0
            for (ref in pending) {
                if (ref.isGallery) galleryRepo.prefetch(ref.id) else fileRepo.prefetch(ref.id)
                report(++done, pending.size)
            }
        }
    }
}
