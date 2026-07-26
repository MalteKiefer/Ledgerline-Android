package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.ImportResult
import de.ledgerline.app.domain.usecase.MutateGallery
import de.ledgerline.app.domain.usecase.PhotoSource
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete gallery import loop. Extracted verbatim from the Gallery ViewModel so the
 * share target reuses the exact same dedup + upload + index-append behaviour. The
 * caller wraps this in an [de.ledgerline.app.core.ops.OperationManager] op and passes
 * the [report] reporter.
 */
@Singleton
class ImportPhotosImpl @Inject constructor(
    private val cache: GalleryCache,
    private val uploader: GalleryUploader,
    private val mutate: MutateGallery,
) : ImportPhotos {

    override suspend fun invoke(sources: List<PhotoSource>, report: (Int, Int) -> Unit): ImportResult {
        val existing = cache.value.value?.manifest?.photos
            ?.mapNotNull { it.sig }
            ?.toMutableSet()
            ?: mutableSetOf()

        report(0, sources.size)
        var done = 0
        var failed = 0
        val failedSources = mutableListOf<PhotoSource>()

        for (src in sources) {
            val sig = try {
                fileSig(src.openInput, src.size)
            } catch (_: Exception) {
                failed++
                failedSources += src
                done++
                report(done, sources.size)
                continue
            }

            if (sig in existing) {
                // Dedup: already present in the gallery index.
                done++
                report(done, sources.size)
                continue
            }

            when (val up = uploader.upload(src.name, src.mime, sig, src.size, src.openInput, nowIso(), src.lat, src.lng)) {
                is Outcome.Ok -> {
                    mutate.invoke { it.copy(photos = it.photos + up.value) }
                    existing += sig
                }
                is Outcome.Err -> { failed++; failedSources += src }
            }
            done++
            report(done, sources.size)
        }

        return ImportResult(done, failed, failedSources)
    }

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
}
