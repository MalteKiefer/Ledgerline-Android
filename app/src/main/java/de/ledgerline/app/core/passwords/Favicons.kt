package de.ledgerline.app.core.passwords

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes site-icon **data-URIs** into Compose [ImageBitmap]s, with an in-memory cache so each
 * unique icon is decoded at most once. Raster payloads (PNG/JPEG/WEBP) decode via
 * [BitmapFactory]; vector/ICO payloads (SVG, `image/x-icon`) that the platform can't decode
 * return null so the caller falls back to the type icon. Zero-knowledge-safe: favicons are
 * fetched server-side and never touch disk here.
 */
object Favicons {
    // Keyed by the full data-URI string. A NONE sentinel lets an undecodable result be cached
    // too (ConcurrentHashMap forbids null values), so we never re-attempt a bad payload.
    private val NONE = Any()
    private val raw = ConcurrentHashMap<String, Any>()

    fun decode(dataUri: String?): ImageBitmap? {
        if (dataUri.isNullOrBlank() || !dataUri.startsWith("data:")) return null
        raw[dataUri]?.let { return if (it === NONE) null else it as ImageBitmap }
        val bmp = runCatching {
            val comma = dataUri.indexOf(',')
            if (comma < 0) return@runCatching null
            val meta = dataUri.substring(5, comma) // between "data:" and ","
            if (!meta.contains("base64")) return@runCatching null // only base64 raster payloads
            val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
        raw[dataUri] = bmp ?: NONE
        return bmp
    }
}
