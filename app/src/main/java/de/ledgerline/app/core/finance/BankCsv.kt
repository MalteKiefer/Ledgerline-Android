package de.ledgerline.app.core.finance

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A parsed bank-statement line (signed [amount], ISO [date]). Mirrors the web `bank-statement.js`
 * shape closely enough for `POST /finance/transactions/bulk` (the server dedups by signature).
 */
data class BankLine(
    val date: String,
    val amount: Double,
    val purpose: String = "",
    val counterparty: String = "",
    val iban: String = "",
    val bic: String = "",
    val bookingText: String = "",
)

/**
 * Minimal, dependency-free CSV bank-statement parser for the common German exports (Sparkasse-style
 * `;`-delimited, DKB/N26-style `,`-delimited). Detects the delimiter, maps columns by fuzzy header
 * keywords, parses German decimal amounts + several date formats. A full MT940/CAMT port is deferred;
 * unknown headers yield no lines (the caller shows "unrecognised format").
 */
object BankCsv {
    private val DATE_KEYS = listOf("buchungstag", "buchungsdatum", "datum", "valuta", "date")
    private val AMOUNT_KEYS = listOf("betrag", "amount", "umsatz")
    private val PURPOSE_KEYS = listOf("verwendungszweck", "purpose", "buchungstext ", "reference")
    private val PARTY_KEYS = listOf("beguenstigter", "begünstigter", "empfänger", "empfaenger", "auftraggeber", "zahlungspflichtiger", "name", "payee", "counterparty")
    private val IBAN_KEYS = listOf("iban", "kontonummer")
    private val BIC_KEYS = listOf("bic", "blz", "swift")
    private val BOOKING_KEYS = listOf("buchungstext", "transaktionstyp", "type")

    fun parse(text: String): List<BankLine> {
        val lines = text.split(Regex("\r\n|\r|\n")).filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val delimiter = detectDelimiter(lines[0])
        val header = splitLine(lines[0], delimiter).map { it.trim().lowercase().removeSurrounding("\"") }

        fun col(keys: List<String>): Int = header.indexOfFirst { h -> keys.any { h.contains(it.trim()) } }
        val di = col(DATE_KEYS); val ai = col(AMOUNT_KEYS)
        if (di < 0 || ai < 0) return emptyList() // need at least a date + amount to be a statement
        val pi = col(PURPOSE_KEYS); val ci = col(PARTY_KEYS); val ii = col(IBAN_KEYS); val bi = col(BIC_KEYS); val bti = col(BOOKING_KEYS)

        return lines.drop(1).mapNotNull { row ->
            val cells = splitLine(row, delimiter)
            fun cell(i: Int) = if (i in cells.indices) cells[i].trim().removeSurrounding("\"") else ""
            val date = parseDate(cell(di)) ?: return@mapNotNull null
            val amount = parseAmount(cell(ai)) ?: return@mapNotNull null
            BankLine(
                date = date, amount = amount,
                purpose = cell(pi), counterparty = cell(ci),
                iban = cell(ii), bic = cell(bi), bookingText = cell(bti),
            )
        }
    }

    fun toJson(l: BankLine): JsonObject = buildJsonObject {
        put("date", l.date)
        put("amount", l.amount.toString())
        if (l.counterparty.isNotBlank()) put("counterparty", l.counterparty)
        if (l.purpose.isNotBlank()) put("purpose", l.purpose)
        if (l.iban.isNotBlank()) put("counterparty_iban", l.iban)
        if (l.bic.isNotBlank()) put("bic", l.bic)
        if (l.bookingText.isNotBlank()) put("booking_text", l.bookingText)
    }

    // ---- primitives (kept public for tests) ----
    fun detectDelimiter(headerLine: String): Char {
        val counts = mapOf(';' to headerLine.count { it == ';' }, ',' to headerLine.count { it == ',' }, '\t' to headerLine.count { it == '\t' })
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ';'
    }

    /** Split one CSV line honouring double-quoted fields (embedded delimiters + "" escapes). */
    fun splitLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** German/plain decimal ("1.992,43", "-175,28", "150.00", "213,51") → signed number, or null. */
    fun parseAmount(s: String): Double? {
        var t = s.trim().replace("€", "").replace(" ", "")
        if (t.isEmpty()) return null
        var sign = 1
        if (t.startsWith("(") && t.endsWith(")")) { sign = -1; t = t.substring(1, t.length - 1) }
        if (t.endsWith("-")) { sign = -1; t = t.dropLast(1) }
        if (t.startsWith("-")) { sign = -1; t = t.drop(1) }
        if (t.startsWith("+")) t = t.drop(1)
        // If both separators present, the last one is the decimal; else a lone ',' is decimal.
        val lastComma = t.lastIndexOf(','); val lastDot = t.lastIndexOf('.')
        t = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            lastComma >= 0 -> t.replace(',', '.')
            else -> t
        }
        val n = t.toDoubleOrNull() ?: return null
        return sign * (Math.round(n * 100.0) / 100.0)
    }

    /** DD.MM.YY[YY], YYYY-MM-DD, DD/MM/YYYY → ISO yyyy-MM-dd, or null. */
    fun parseDate(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(t)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{2,4})""").find(t)?.let {
            val d = it.groupValues[1].padStart(2, '0'); val m = it.groupValues[2].padStart(2, '0')
            var y = it.groupValues[3]; if (y.length == 2) y = "20$y"
            return "$y-$m-$d"
        }
        return null
    }
}
