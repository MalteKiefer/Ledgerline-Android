package de.ledgerline.app.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** CameraX ImageAnalysis analyzer decoding QR codes with ZXing (Apache-2, no GMS). */
class QrCodeAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    @Volatile private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) { image.close(); return }
        val plane = image.planes[0]
        val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        try {
            val text = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            if (text.startsWith("ledgerline://pair")) { done = true; onResult(text) }
        } catch (_: Exception) {
            // no code in this frame
        } finally {
            image.close()
        }
    }
}

/** Parses a ledgerline://pair deep link into (baseUrl, code) or null if invalid. */
fun parsePairLink(uri: String): Pair<String, String>? {
    // Use java.net.URI so this function is testable on JVM without Robolectric.
    return try {
        val parsed = java.net.URI(uri)
        if (parsed.scheme != "ledgerline" || parsed.host != "pair") return null
        val query = parsed.rawQuery ?: return null
        val params = query.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx < 0) part to "" else part.substring(0, idx) to java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }
        val url = params["url"] ?: return null
        val code = params["code"] ?: return null
        if (!url.startsWith("https://")) return null
        // The pairing code is a 256-bit one-time token; reject anything outside a sane
        // length/charset so a crafted deep link can't push arbitrary bytes at the server (L3).
        if (code.length !in 1..512 || !code.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        url to code
    } catch (_: Exception) { null }
}
