package de.ledgerline.app.core.finance

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.Invoice
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import java.util.Locale

/**
 * Renders an [Invoice] to a single-page A4 PDF on-device (ZK — the PDF is built from the decrypted
 * invoice + company profile and only leaves the device as an e-mail attachment via the user's own
 * SMTP, POST /invoices/send). Clean layout: company header, customer block, line-item table,
 * per-rate VAT + gross totals, optional note/footer. Not pixel-identical to the web template, but a
 * correct, legible GoBD-style document.
 */
object InvoicePdf {
    private const val W = 595 // A4 @ 72dpi
    private const val H = 842
    private const val M = 46f // margin
    private val euro: NumberFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)

    private fun money(v: Double, currency: String) = runCatching { euro.format(v) }.getOrDefault("$v $currency")

    fun render(inv: Invoice, company: CompanyProfile): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val cv = page.canvas

        val h1 = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        val label = Paint().apply { color = Color.rgb(90, 90, 100); textSize = 9f; isAntiAlias = true }
        val value = Paint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
        val bold = Paint().apply { color = Color.BLACK; textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        val small = Paint().apply { color = Color.rgb(120, 120, 130); textSize = 9f; isAntiAlias = true }
        val rule = Paint().apply { color = Color.rgb(210, 210, 220); strokeWidth = 1f }
        val right = Paint(value).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(bold).apply { textAlign = Paint.Align.RIGHT }

        var y = M + 10f
        // Company header (right-aligned identity).
        cv.drawText(company.name.ifBlank { " " }, W - M, y, Paint(h1).apply { textAlign = Paint.Align.RIGHT })
        y += 16f
        company.address.split("\n").forEach { if (it.isNotBlank()) { cv.drawText(it, W - M, y, Paint(small).apply { textAlign = Paint.Align.RIGHT }); y += 11f } }
        listOfNotNull(company.email.ifBlank { null }, company.phone.ifBlank { null }, company.vatId.ifBlank { null }?.let { "USt-IdNr. $it" })
            .forEach { cv.drawText(it, W - M, y, Paint(small).apply { textAlign = Paint.Align.RIGHT }); y += 11f }

        // Customer block (left).
        var cy = M + 34f
        cv.drawText("RECHNUNG AN", M, cy, label); cy += 16f
        cv.drawText(inv.customer.name.ifBlank { " " }, M, cy, bold); cy += 14f
        if (inv.customer.attn.isNotBlank()) { cv.drawText("z.Hd. ${inv.customer.attn}", M, cy, value); cy += 13f }
        inv.customer.address.split("\n").forEach { if (it.isNotBlank()) { cv.drawText(it, M, cy, value); cy += 13f } }
        if (inv.customer.vatId.isNotBlank()) { cv.drawText("USt-IdNr. ${inv.customer.vatId}", M, cy, small); cy += 12f }

        y = maxOf(y, cy) + 24f
        // Invoice meta.
        cv.drawText("Rechnung ${inv.number ?: "(Entwurf)"}", M, y, h1); y += 20f
        val meta = listOfNotNull(
            inv.issueDate.ifBlank { null }?.let { "Datum: $it" },
            inv.dueDate.ifBlank { null }?.let { "Fällig: $it" },
        ).joinToString("    ")
        if (meta.isNotBlank()) { cv.drawText(meta, M, y, value); y += 18f }

        // Table header.
        y += 8f
        cv.drawLine(M, y, W - M, y, rule); y += 14f
        cv.drawText("Beschreibung", M, y, label)
        cv.drawText("Menge", W - M - 230f, y, Paint(label).apply { textAlign = Paint.Align.RIGHT })
        cv.drawText("Einzel", W - M - 120f, y, Paint(label).apply { textAlign = Paint.Align.RIGHT })
        cv.drawText("USt%", W - M - 70f, y, Paint(label).apply { textAlign = Paint.Align.RIGHT })
        cv.drawText("Netto", W - M, y, Paint(label).apply { textAlign = Paint.Align.RIGHT })
        y += 6f; cv.drawLine(M, y, W - M, y, rule); y += 16f

        inv.lines.forEach { l ->
            val n = InvoiceMath.lineNet(l)
            cv.drawText(l.desc.take(48), M, y, value)
            cv.drawText(fmtNum(l.qty) + (if (l.unit.isNotBlank()) " ${l.unit}" else ""), W - M - 230f, y, right)
            cv.drawText(money(l.unitPrice, inv.currency), W - M - 120f, y, right)
            cv.drawText(fmtNum(l.vatRate), W - M - 70f, y, right)
            cv.drawText(money(n, inv.currency), W - M, y, right)
            y += 16f
        }
        y += 4f; cv.drawLine(M, y, W - M, y, rule); y += 18f

        val t = InvoiceMath.totals(inv)
        cv.drawText("Netto", W - M - 120f, y, label); cv.drawText(money(t.net, inv.currency), W - M, y, right); y += 15f
        t.vatByRate.toSortedMap().forEach { (rate, v) ->
            cv.drawText("USt ${fmtNum(rate)}%", W - M - 120f, y, label); cv.drawText(money(v, inv.currency), W - M, y, right); y += 15f
        }
        cv.drawText("Gesamt", W - M - 120f, y, bold); cv.drawText(money(t.gross, inv.currency), W - M, y, rightBold); y += 22f

        if (inv.note.isNotBlank()) { y += 6f; inv.note.split("\n").forEach { cv.drawText(it, M, y, value); y += 13f } }
        val footer = inv.footer.ifBlank { company.address.replace("\n", " ") }
        if (footer.isNotBlank()) cv.drawText(footer.take(110), M, H - M, small)

        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }

    private fun fmtNum(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
