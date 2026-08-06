package de.ledgerline.app.core.offline

import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.data.ContactBlobRepository
import de.ledgerline.app.data.FileBlobRepository
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
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
    private val workspaceCache: WorkspaceCache,
    private val fileRepo: FileBlobRepository,
    private val contactRepo: ContactBlobRepository,
    private val blobCache: BlobDiskCache,
    private val offlineFlags: OfflineFlags,
    private val constraints: Constraints,
    private val operationManager: OperationManager,
) {
    private enum class Kind { FILE, CONTACT }

    /** A blob ref plus which store it belongs to (routes to the right repo). */
    private data class Ref(val id: String, val kind: Kind)

    private val _message = MutableStateFlow<String?>(null)

    /** Surfaces a manual-path reason (currently `"constraints"`) for the UI to show. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** Manual "Prefetch now": runs whenever any policy caches something; honours constraints. */
    fun prefetchNow() = run(auto = false)

    /** Auto on unlock: only runs when a prefetch policy (ALL) is active. */
    fun maybePrefetchOnUnlock() = run(auto = true)

    private fun run(auto: Boolean) {
        if (!offlineFlags.enabled()) return

        val filesPolicy = offlineFlags.filesPolicy()
        val contactsPolicy = offlineFlags.contactsPolicy()

        val filesIsPrefetch = filesPolicy == FileBlobPolicy.ALL
        val contactsIsPrefetch = contactsPolicy == ContactBlobPolicy.ALL

        // Nothing caches anything → no-op on both paths (auto and manual).
        if (!filesIsPrefetch && !contactsIsPrefetch) return

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

        if (filesIsPrefetch) {
            val files = workspaceCache.value.value?.manifest?.files.orEmpty().filter { !it.trashed }
            for (f in files) {
                if (f.blob.isNotEmpty()) refs.add(Ref(f.blob, Kind.FILE))
            }
        }

        if (contactsIsPrefetch) {
            val contacts = workspaceCache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }
            for (c in contacts) {
                c.avatarRef?.takeIf { it.isNotEmpty() }?.let { refs.add(Ref(it, Kind.CONTACT)) }
            }
        }

        // Dedup, drop already-cached.
        val pending = refs.distinctBy { it.id }.filterNot { blobCache.has(it.id) }
        if (pending.isEmpty()) return

        // Files are fetched in batches (one raw-batch round-trip per ≤512 ids); contacts stay
        // per-blob (their store has no batch endpoint).
        val fileRefs = pending.filter { it.kind == Kind.FILE }.map { it.id }
        val others = pending.filter { it.kind == Kind.CONTACT }

        operationManager.run(OpKind.PREFETCH, total = pending.size) { report ->
            var done = 0
            for (chunk in fileRefs.chunked(512)) {
                fileRepo.prefetchBatch(chunk)
                done += chunk.size
                report(done, pending.size)
            }
            for (ref in others) {
                when (ref.kind) {
                    Kind.CONTACT -> contactRepo.prefetch(ref.id)
                    else -> Unit // files handled in the batch above
                }
                report(++done, pending.size)
            }
        }
    }
}
