package de.ledgerline.app.core.finance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Renders an Eigenbeleg [Eigenbeleg.Draft] to a single-page A4 PDF on-device (ZK — never leaves the
 * client except as the sealed receipt blob). A plain paper-form layout mirroring the user's manual
 * voucher: header, the booking/amount facts, the missing-original reason, issuer + signature, and
 * the "no input-VAT deduction" note. German (it is a German tax document).
 */
object EigenbelegPdf {
    private const val W = 595 // A4 @ 72dpi
    private const val H = 842
    private const val M = 48f // margin

    private fun grundLabel(g: String) = when (g) {
        "privatentnahme" -> "Privatentnahme"
        "privateinlage" -> "Privateinlage"
        "trinkgeld" -> "Trinkgeld"
        "betriebsausgabe" -> "Betriebsausgabe (Beleg verloren)"
        "sachgeschenk" -> "Sachgeschenk"
        else -> "Sonstiges"
    }

    private fun money(v: Double) = String.format(java.util.Locale.GERMANY, "%,.2f €", v)

    fun render(d: Eigenbeleg.Draft): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val cv = page.canvas
        val title = Paint().apply { color = Color.BLACK; textSize = 22f; isFakeBoldText = true; isAntiAlias = true }
        val label = Paint().apply { color = Color.rgb(90, 90, 100); textSize = 10f; isAntiAlias = true }
        val value = Paint().apply { color = Color.BLACK; textSize = 13f; isAntiAlias = true }
        val rule = Paint().apply { color = Color.rgb(210, 210, 220); strokeWidth = 1f }
        val note = Paint().apply { color = Color.rgb(120, 120, 130); textSize = 9.5f; isAntiAlias = true }

        cv.drawText("Eigenbeleg", M, 70f, title)
        cv.drawText(grundLabel(d.grund) + (if (d.grund == "sonstiges" && d.grundOther.isNotBlank()) " – ${d.grundOther}" else ""), M, 90f, value.apply { textSize = 13f })
        cv.drawLine(M, 104f, W - M, 104f, rule)

        var y = 134f
        fun field(l: String, v: String) {
            if (v.isBlank()) return
            cv.drawText(l.uppercase(), M, y, label)
            y += 16f
            // wrap long values crudely at ~78 chars
            v.chunked(78).forEach { line -> cv.drawText(line, M, y, value); y += 18f }
            y += 8f
        }
        field("Datum", d.date)
        field("Empfänger / Zahlungsempfänger", d.recipient)
        field("Anschrift", d.address)
        field("Buchungstext", d.buchungstext)

        // Amount block
        cv.drawLine(M, y, W - M, y, rule); y += 24f
        cv.drawText("BETRAG (BRUTTO)", M, y, label)
        cv.drawText(money(d.gross), W - M - value.measureText(money(d.gross)), y, value.apply { textSize = 13f }); y += 20f
        cv.drawText("Netto", M, y, label); cv.drawText(money(d.net), W - M - value.measureText(money(d.net)), y, value); y += 18f
        cv.drawText("USt ${trim(d.vatRate)} %", M, y, label); cv.drawText(money(d.vat), W - M - value.measureText(money(d.vat)), y, value); y += 26f
        cv.drawLine(M, y, W - M, y, rule); y += 24f

        field("Grund für den fehlenden Originalbeleg", d.reason)
        field("Aussteller", d.issuer)
        field("Ort", d.ort)

        // Signature
        y += 10f
        cv.drawText("UNTERSCHRIFT", M, y, label); y += 8f
        decodeSignature(d.signature)?.let { bmp ->
            val h = 60; val w = (bmp.width.toFloat() / bmp.height * h).toInt().coerceAtMost(240)
            cv.drawBitmap(bmp, null, Rect(M.toInt(), y.toInt(), M.toInt() + w, y.toInt() + h), null)
            y += h.toFloat()
        }
        cv.drawLine(M, y + 6f, M + 240f, y + 6f, rule); y += 30f

        cv.drawText("Hinweis: Aus einem Eigenbeleg ist kein Vorsteuerabzug möglich.", M, y, note)
        cv.drawText("Erstellt am ${d.createdAt} · Ledgerline", M, H - 40f, note)

        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun trim(v: Double) = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()

    private fun decodeSignature(uri: String): Bitmap? {
        if (!uri.startsWith("data:")) return null
        val b64 = uri.substringAfter("base64,", "").takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.decode(b64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
}
