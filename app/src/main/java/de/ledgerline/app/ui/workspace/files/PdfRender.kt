package de.ledgerline.app.ui.workspace.files

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable

/**
 * Renders a PDF held entirely in memory to page bitmaps.
 *
 * The decrypted PDF bytes are never written to disk — [PDDocument] is opened from a
 * [ByteArrayInputStream] over the in-memory [ByteArray]. One document/renderer is kept
 * alive for the viewer session and [close]d when the viewer leaves composition.
 *
 * PdfBox is not thread-safe on a single [PDDocument]; page renders are serialized with a
 * [Mutex] and run off the main thread.
 */
class PdfRender private constructor(
    private val document: PDDocument,
    private val renderer: PDFRenderer,
) : Closeable {

    /** Serializes access to the (non-thread-safe) single document. */
    private val renderMutex = Mutex()

    val pageCount: Int get() = document.numberOfPages

    /**
     * Renders the page at [index] to a bitmap roughly [targetWidthPx] wide.
     * Runs off the main thread and is serialized so only one render touches the document
     * at a time. Returns `null` if this page fails to render.
     */
    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        renderMutex.withLock {
            runCatching {
                val pageWidthPt = document.getPage(index).mediaBox.width.takeIf { it > 0f } ?: DEFAULT_PAGE_WIDTH_PT
                // Scale so the page is ~targetWidthPx wide, capped to keep bitmaps reasonable.
                val rawScale = targetWidthPx / pageWidthPt
                val scale = rawScale.coerceIn(MIN_SCALE, MAX_SCALE)
                renderer.renderImage(index, scale, ImageType.RGB)
            }.getOrNull()
        }
    }

    override fun close() {
        runCatching { document.close() }
    }

    companion object {
        private const val DEFAULT_PAGE_WIDTH_PT = 612f // US Letter width in points
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 4f

        /**
         * Opens a [PdfRender] over the in-memory [bytes] off the main thread.
         * Returns `null` if the PDF can't be opened (corrupt/unsupported).
         */
        suspend fun open(bytes: ByteArray): PdfRender? = withContext(Dispatchers.IO) {
            runCatching {
                val doc = PDDocument.load(ByteArrayInputStream(bytes))
                PdfRender(doc, PDFRenderer(doc))
            }.getOrNull()
        }
    }
}
