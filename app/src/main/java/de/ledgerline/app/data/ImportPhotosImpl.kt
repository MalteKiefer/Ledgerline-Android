package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.ImportResult
import de.ledgerline.app.domain.usecase.MutateGallery
import de.ledgerline.app.domain.usecase.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete gallery import loop, shared by the Gallery screen, the share target and the
 * camera-roll backup. Behaviour, byte-for-byte, per photo is unchanged from the old
 * sequential loop; what changed is the *shape* of the run:
 *
 *  - **Bounded concurrency** ([LANES] photos encrypt+upload in parallel) so a backup of a
 *    thousand small photos isn't serialised behind one network round-trip at a time.
 *  - **Batched index commit**: instead of one full sealed-store PUT *per photo* (each PUT
 *    re-seals + re-uploads the whole sharded root), successful entries are committed in
 *    [COMMIT_BATCH]-sized `MutateGallery` writes. Commits run sequentially (never
 *    concurrently) so the optimistic-version store stays consistent.
 *  - **Quota-aware**: a 413 stops queuing further uploads and surfaces `quotaExceeded`.
 *
 * Durability is preserved: an entry is reported as *succeeded* (not in [ImportResult.failedSources])
 * ONLY after its batch commit lands. A commit failure moves the whole batch back to failed, so the
 * caller never marks/deletes an original whose photo isn't actually in the index.
 */
@Singleton
class ImportPhotosImpl @Inject constructor(
    private val cache: GalleryCache,
    private val uploader: GalleryUploader,
    private val mutate: MutateGallery,
    private val importQueue: ImportQueue,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
) : ImportPhotos {

    override suspend fun invoke(sources: List<PhotoSource>, report: (Int, Int) -> Unit, queue: Boolean): ImportResult {
        // Sigs already in the index PLUS sigs reserved this run — a single guarded set so two
        // identical new photos in the same batch don't both upload (atomic check-and-reserve).
        val seen = HashSet(cache.value.value?.manifest?.photos?.mapNotNull { it.sig }.orEmpty())
        val seenLock = Mutex()

        val total = sources.size
        report(0, total)
        val progress = AtomicInteger(0)
        val quota = AtomicBoolean(false)
        val failed = java.util.Collections.synchronizedList(mutableListOf<PhotoSource>())
        val queued = java.util.Collections.synchronizedList(mutableListOf<PhotoSource>())
        // Successful uploads awaiting index commit, paired with their source for failure roll-back.
        val uploaded = java.util.Collections.synchronizedList(mutableListOf<Pair<PhotoSource, GalleryPhoto>>())

        val vk = vaultKeyHolder.get()

        // Offline (with an unlocked vault): don't attempt a doomed upload — seal each fresh source to
        // the durable import queue and report it as queued. Replay runs the full pipeline on reconnect.
        if (queue && vk != null && !connectivity.isOnline()) {
            for (src in sources) {
                val sig = try { fileSig(src.openInput, src.size) } catch (_: Exception) { failed += src; tick(progress, total, report); continue }
                if (!seen.add(sig)) { tick(progress, total, report); continue } // already present
                importQueue.enqueuePhoto(vk, src.name, src.mime, src.size, sig, src.openInput, src.lat, src.lng)
                queued += src
                tick(progress, total, report)
            }
            return ImportResult(total, failed.size, failed.toList(), false, queued.toList())
        }

        coroutineScope {
            val sem = Semaphore(LANES)
            sources.map { src ->
                async(Dispatchers.Default) {
                    sem.withPermit {
                        // Once the account is full, stop spending effort on the rest of the batch.
                        if (quota.get()) { failed += src; tick(progress, total, report); return@withPermit }

                        val sig = try {
                            fileSig(src.openInput, src.size)
                        } catch (_: Exception) {
                            failed += src; tick(progress, total, report); return@withPermit
                        }

                        // Atomic reserve: add() is false when the sig is already present or reserved.
                        val fresh = seenLock.withLock { seen.add(sig) }
                        if (!fresh) { tick(progress, total, report); return@withPermit } // dedup

                        when (val up = uploader.upload(src.name, src.mime, sig, src.size, src.openInput, nowIso(), src.lat, src.lng)) {
                            is Outcome.Ok -> uploaded += src to up.value
                            is Outcome.Err -> {
                                if (up.kind == ErrorKind.QUOTA) quota.set(true)
                                // A recoverable upload failure (dropped socket, 5xx, 429) is not a loss:
                                // seal the source to the durable queue and keep the sig reserved so it
                                // isn't re-uploaded this run. QUOTA / hard errors go to failed as before.
                                if (queue && vk != null && up.kind in de.ledgerline.app.core.offline.RECOVERABLE_SAVE_ERRORS) {
                                    importQueue.enqueuePhoto(vk, src.name, src.mime, src.size, sig, src.openInput, src.lat, src.lng)
                                    queued += src
                                } else {
                                    failed += src
                                    seenLock.withLock { seen.remove(sig) } // free the reservation for a retry
                                }
                            }
                        }
                        tick(progress, total, report)
                    }
                }
            }.awaitAll()
        }

        // Commit successes to the index in batches — sequentially, so the optimistic-version
        // store never sees concurrent writers. A failed commit demotes its batch to failed.
        val committed = uploaded.toList() // snapshot (the synchronized list is done being written)
        for (batch in committed.chunked(COMMIT_BATCH)) {
            val res = mutate.invoke { m -> m.copy(photos = m.photos + batch.map { it.second }) }
            if (res is Outcome.Err) failed += batch.map { it.first }
        }

        return ImportResult(
            done = total,
            failed = failed.size,
            failedSources = failed.toList(),
            quotaExceeded = quota.get(),
            queuedSources = queued.toList(),
        )
    }

    private fun tick(progress: AtomicInteger, total: Int, report: (Int, Int) -> Unit) =
        report(progress.incrementAndGet(), total)

    /**
     * Duplicate signature, byte-compatible with the web `_fileSig`:
     * `"${size}:${hex(sha256(first1MiB ++ last1MiB))}"` (tail omitted when size <= 1 MiB).
     * Reads only the first + last 1 MiB by (re-)streaming from [openInput] — never the whole file.
     */
    private fun fileSig(openInput: () -> java.io.InputStream, size: Long): String {
        val cap = 1024 * 1024
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(minOf(cap.toLong(), size).toInt())
        openInput().use { readFully(it, head) }
        md.update(head)
        if (size > cap) {
            val tail = ByteArray(cap)
            openInput().use { ins -> skipFully(ins, size - cap); readFully(ins, tail) }
            md.update(tail)
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "$size:$hex"
    }

    /** Read exactly [buf].size bytes (or throw). */
    private fun readFully(ins: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    /** Skip exactly [n] bytes (InputStream.skip may skip fewer). */
    private fun skipFully(ins: java.io.InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s > 0) { left -= s; continue }
            if (ins.read() < 0) throw java.io.EOFException()
            left--
        }
    }

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private companion object {
        /** Photos encrypting+uploading in parallel. Small — kind to mobile data, server throttles, and
         *  peak memory: each lane buffers a photo's `/gallery/process` base64 renditions in RAM, so 4
         *  lanes on a large-photo batch saturated the heap and ANR-killed the app (verified on-device). */
        const val LANES = 2
        /** Index entries per sealed-store PUT (vs. one PUT per photo before). */
        const val COMMIT_BATCH = 8
    }
}
