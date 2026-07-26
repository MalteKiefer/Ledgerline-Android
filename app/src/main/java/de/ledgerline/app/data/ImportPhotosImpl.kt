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
            val bytes = try {
                src.read()
            } catch (_: Exception) {
                failed++
                failedSources += src
                done++
                report(done, sources.size)
                continue
            }

            val sig = fileSig(bytes)
            if (sig in existing) {
                // Dedup: already present in the gallery index.
                done++
                report(done, sources.size)
                continue
            }

            when (val up = uploader.upload(src.name, src.mime, sig, bytes, nowIso(), src.lat, src.lng)) {
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

    /** Duplicate signature, byte-compatible with the web `_fileSig`:
     *  "${size}:${hex(sha256(first1MiB ++ last1MiB))}" (tail empty when size <= 1 MiB). */
    private fun fileSig(bytes: ByteArray): String {
        val cap = 1024 * 1024
        val size = bytes.size
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, 0, minOf(cap, size))
        if (size > cap) md.update(bytes, size - cap, cap)
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "$size:$hex"
    }

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
}
