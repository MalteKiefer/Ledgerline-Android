package de.ledgerline.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream

/**
 * A fast, native signature pad. Drawing happens in a plain [View] (a persistent backing [Bitmap] +
 * `Path`, invalidating only the view per touch) — NOT a Compose Canvas that recomposes on every
 * point, which was unusably laggy. Black ink on transparent; flattened to a PNG `data:` URI on demand.
 * No third-party lib.
 */
@SuppressLint("ClickableViewAccessibility")
class SignatureView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val path = Path()
    private val paint = Paint().apply {
        color = Color.BLACK; isAntiAlias = true; style = Paint.Style.STROKE
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var lastX = 0f
    private var lastY = 0f
    private var seed: String? = null
    var empty: Boolean = true
        private set
    /** Called after each finished stroke so the caller can persist the fresh signature. */
    var onStrokeEnd: (() -> Unit)? = null

    /** Preload an existing signature (PNG data-URI) so re-opening keeps prior strokes. */
    fun seedFrom(dataUri: String?) { seed = dataUri; if (width > 0) applySeed() }

    private fun applySeed() {
        val uri = seed ?: return
        seed = null
        if (!uri.startsWith("data:")) return
        val b64 = uri.substringAfter("base64,", "").ifBlank { return }
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { src ->
            canvas?.drawBitmap(Bitmap.createScaledBitmap(src, width, height, true), 0f, 0f, null)
            empty = false; invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = bmp; canvas = Canvas(bmp)
        applySeed()
    }

    override fun onDraw(c: Canvas) {
        bitmap?.let { c.drawBitmap(it, 0f, 0f, null) }
        c.drawPath(path, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { path.moveTo(x, y); lastX = x; lastY = y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2); lastX = x; lastY = y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                path.lineTo(x, y)
                canvas?.drawPath(path, paint)
                path.reset(); empty = false
                onStrokeEnd?.invoke()
            }
            else -> return false
        }
        invalidate()
        return true
    }

    fun clear() {
        bitmap?.eraseColor(Color.TRANSPARENT); path.reset(); empty = true; invalidate()
        onStrokeEnd?.invoke()
    }

    /** Flatten to a PNG `data:` URI, or "" when nothing was drawn. */
    fun toDataUri(): String {
        val bmp = bitmap ?: return ""
        if (empty) return ""
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** Holds the current [SignatureView] so callers can read/clear it without recomposition. */
class SignatureController {
    internal var view: SignatureView? = null
    fun dataUri(): String = view?.toDataUri() ?: ""
    fun clear() = view?.clear() ?: Unit
    val isEmpty: Boolean get() = view?.empty ?: true
}

/** A native signature pad. [initialUri] seeds prior strokes; [onChanged] fires after each stroke. */
@Composable
fun SignaturePad(
    controller: SignatureController,
    modifier: Modifier = Modifier,
    initialUri: String = "",
    onChanged: (String) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SignatureView(ctx).apply {
                controller.view = this
                if (initialUri.isNotBlank()) seedFrom(initialUri)
                onStrokeEnd = { onChanged(toDataUri()) }
            }
        },
    )
}
