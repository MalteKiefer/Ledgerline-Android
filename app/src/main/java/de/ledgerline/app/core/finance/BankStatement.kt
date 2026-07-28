package de.ledgerline.app.core.finance

/**
 * Bank-statement parsing for the Finance module — a faithful port of the web
 * `shared/bank-statement.js`. Pure + testable; all parsing runs client-side (zero-knowledge). Hybrid
 * strategy: auto-detect the well-known formats (MT940, recognised bank CSVs) and fall back to a
 * user-driven column mapping for any other CSV. Everything normalises to [ParsedTx].
 */
object BankStatement {

    /** A parsed statement line (pre-import; becomes a `Transaction` once assigned an account). */
    data class ParsedTx(
        val date: String,
        val valueDate: String = "",
        val amount: Double = 0.0,
        val currency: String = "EUR",
        val purpose: String = "",
        val counterparty: String = "",
        val iban: String = "",
        val bic: String = "",
        val bookingText: String = "",
        val eref: String = "",
        val category: String = "",
    )

    // ---- value helpers ----

    /** Parse a German/decimal amount ("1.992,43", "-175,28", "150.00") → number, or null. */
    fun parseAmount(s: String?): Double? {
        var t = (s ?: "").trim().replace(Regex("\\s| |EUR|€", RegexOption.IGNORE_CASE), "")
        if (t.isEmpty()) return null
        var sign = 1
        if (Regex("^\\(.*\\)$").matches(t)) { sign = -1; t = t.substring(1, t.length - 1) }
        if (t.endsWith("-")) { sign = -1; t = t.dropLast(1) }
        if (t.startsWith("-")) { sign = -1; t = t.drop(1) }
        t = t.removePrefix("+")
        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        t = when {
            lastComma > lastDot -> t.replace(".", "").replace(",", ".")   // 1.992,43
            lastDot > lastComma -> t.replace(",", "")                     // 1,992.43
            else -> t.replace(",", ".")
        }
        val n = t.toDoubleOrNull() ?: return null
        return sign * Math.round(n * 100.0) / 100.0
    }

    /** Normalise a date (DD.MM.YY[YY], YYYY-MM-DD, YYMMDD, DD/MM/YYYY) → ISO yyyy-mm-dd, or null. */
    fun parseDate(s: String?): String? {
        val t = (s ?: "").trim()
        if (t.isEmpty()) return null
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(t)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{2}|\\d{4})").find(t)?.let {
            val y = if (it.groupValues[3].length == 2) "20" + it.groupValues[3] else it.groupValues[3]
            return "$y-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[1].padStart(2, '0')}"
        }
        Regex("^(\\d{2})(\\d{2})(\\d{2})$").find(t)?.let { return "20${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        return null
    }

    // ---- format detection ----

    /** `mt940` | `csv` | `unknown` from the file's content (+ optional name). */
    fun detectFormat(text: String?, filename: String = ""): String {
        val s = text ?: ""
        if (Regex("^:20:", RegexOption.MULTILINE).containsMatchIn(s) && Regex("^:61:", RegexOption.MULTILINE).containsMatchIn(s)) return "mt940"
        if (Regex("\\.sta$|\\.mt940$", RegexOption.IGNORE_CASE).containsMatchIn(filename) && s.contains(":61:")) return "mt940"
        if (Regex("[,;\\t]").containsMatchIn(s.split(Regex("\\r?\\n")).firstOrNull() ?: "")) return "csv"
        return "unknown"
    }

    // ---- MT940 ----

    private val MT940_DC = mapOf("C" to 1, "D" to -1, "RC" to -1, "RD" to 1)

    /** Parse the ?NN sub-fields of an MT940 :86: field. */
    fun parseMt940Field86(raw: String?): ParsedTx {
        val parts = (raw ?: "").split("?").drop(1)
        val purpose = StringBuilder(); val name = StringBuilder()
        var bookingText = ""; var bic = ""; var iban = ""
        for (p in parts) {
            val code = p.take(2); val v = p.drop(2)
            val n = code.toIntOrNull()
            when {
                code == "00" -> bookingText = v.trim()
                n != null && n in 20..29 -> purpose.append(v)
                code == "30" -> bic = v.trim()
                code == "31" -> iban = v.trim()
                code == "32" || code == "33" -> name.append(v)
            }
        }
        var purposeStr = purpose.toString().replace(Regex("\\s+"), " ").trim()
        val counterparty = name.toString().replace(Regex("\\s+"), " ").trim()
        val eref = Regex("EREF\\+(\\S+)", RegexOption.IGNORE_CASE).find(purposeStr)?.groupValues?.get(1) ?: ""
        Regex("SVWZ\\+(.*)$", RegexOption.IGNORE_CASE).find(purposeStr)?.let { purposeStr = it.groupValues[1].trim() }
        return ParsedTx(date = "", purpose = purposeStr, counterparty = counterparty, iban = iban, bic = bic, bookingText = bookingText, eref = eref)
    }

    data class Mt940Result(
        val transactions: List<ParsedTx>,
        val openingBalance: Double?,
        val closingBalance: Double?,
        val currency: String,
        val account: String,
    )

    /** Parse an MT940 statement (handles concatenated statements + folded :86: lines). */
    fun parseMt940(text: String?): Mt940Result {
        val rawLines = (text ?: "").split(Regex("\\r?\\n"))
        val lines = mutableListOf<String>()
        for (ln in rawLines) {
            if (Regex("^:\\w{2,3}:").containsMatchIn(ln) || ln == "-") lines.add(ln)
            else if (lines.isNotEmpty()) lines[lines.size - 1] += ln
        }
        val txns = mutableListOf<ParsedTx>()
        var currency = "EUR"; var account = ""; var opening: Double? = null; var closing: Double? = null
        var pending: ParsedTx? = null
        fun pushPending() { pending?.let { txns.add(it); pending = null } }
        for (ln in lines) {
            val tag = Regex("^:(\\w{2,3}):").find(ln)?.groupValues?.get(1)
            val body = ln.replace(Regex("^:\\w{2,3}:"), "")
            when {
                tag == "25" -> account = body.trim()
                tag == "60F" || tag == "60M" -> Regex("^([CD])(\\d{6})([A-Z]{3})([\\d.,]+)").find(body)?.let { m ->
                    currency = m.groupValues[3]
                    if (opening == null) opening = (if (m.groupValues[1] == "D") -1 else 1) * (parseAmount(m.groupValues[4]) ?: 0.0)
                }
                tag == "62F" || tag == "62M" -> Regex("^([CD])(\\d{6})([A-Z]{3})([\\d.,]+)").find(body)?.let { m ->
                    currency = m.groupValues[3]
                    closing = (if (m.groupValues[1] == "D") -1 else 1) * (parseAmount(m.groupValues[4]) ?: 0.0)
                }
                tag == "61" -> {
                    pushPending()
                    Regex("^(\\d{6})(\\d{4})?(RC|RD|C|D)([A-Z])?([\\d.,]+)").find(body)?.let { m ->
                        val sign = MT940_DC[m.groupValues[3]] ?: 1
                        val valueDate = parseDate(m.groupValues[1]) ?: ""
                        val entry = if (m.groupValues[2].isNotEmpty() && valueDate.length >= 4)
                            "${valueDate.take(4)}-${m.groupValues[2].take(2)}-${m.groupValues[2].drop(2)}" else valueDate
                        pending = ParsedTx(date = entry, valueDate = valueDate, amount = sign * (parseAmount(m.groupValues[5]) ?: 0.0), currency = currency)
                    }
                }
                tag == "86" && pending != null -> {
                    val f = parseMt940Field86(body)
                    pending = pending!!.copy(purpose = f.purpose, counterparty = f.counterparty, iban = f.iban, bic = f.bic, bookingText = f.bookingText, eref = f.eref)
                }
            }
        }
        pushPending()
        return Mt940Result(txns, opening, closing, currency, account)
    }

    // ---- CSV ----

    private fun splitLine(line: String, delim: Char): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q -> when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    c == '"' -> q = false
                    else -> cur.append(c)
                }
                c == '"' -> q = true
                c == delim -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }
    }

    data class Csv(val delimiter: Char, val header: List<String>, val rows: List<List<String>>)

    /** Parse CSV text; detects `;`, `,` or tab as the delimiter (most-frequent in the header line). */
    fun parseCsv(text: String?): Csv {
        val lines = (text ?: "").split(Regex("\\r?\\n")).filter { it.trim().isNotEmpty() }
        if (lines.isEmpty()) return Csv(';', emptyList(), emptyList())
        val first = lines[0]
        val delimiter = listOf(';', '\t', ',').maxByOrNull { d -> first.count { it == d } } ?: ';'
        val header = splitLine(lines[0], delimiter)
        val rows = lines.drop(1).map { splitLine(it, delimiter) }
        return Csv(delimiter, header, rows)
    }

    val TX_FIELDS = listOf("date", "valueDate", "amount", "purpose", "counterparty", "iban", "bic", "bookingText", "eref", "category")
    val TX_REQUIRED = listOf("date", "amount")
    val VAT_CATS = listOf("19", "16", "7", "0", "private")

    /** Best-effort guess of a booking's VAT category from its category/purpose text; '' if unsure. */
    fun guessVatCat(tx: ParsedTx): String {
        val s = "${tx.category} ${tx.bookingText} ${tx.purpose}".lowercase()
        if (Regex("privat|einlage|entnahme|privateinlage|privatentnahme|einkommensteuer|gehalt|lohn").containsMatchIn(s)) return "private"
        Regex("(\\d{1,2})\\s*%").find(s)?.let { if (it.groupValues[1] in VAT_CATS) return it.groupValues[1] }
        if (Regex("umsatzsteuerfrei|steuerfrei|0\\s*%").containsMatchIn(s)) return "0"
        return ""
    }

    private data class KnownCsv(val name: String, val needs: List<String>, val map: Map<String, String>)
    private val KNOWN_CSV = listOf(
        KnownCsv(
            "sparkasse", listOf("Buchungstag", "Verwendungszweck", "Betrag"),
            mapOf("date" to "Buchungstag", "valueDate" to "Valutadatum", "amount" to "Betrag", "currency" to "Waehrung", "purpose" to "Verwendungszweck", "counterparty" to "Beguenstigter/Zahlungspflichtiger", "iban" to "Kontonummer", "bic" to "BLZ", "bookingText" to "Buchungstext"),
        ),
        KnownCsv(
            "generic-iso", listOf("Buchungsdatum", "Empfänger", "Betrag"),
            mapOf("date" to "Buchungsdatum", "valueDate" to "Wertstellungsdatum", "amount" to "Betrag", "purpose" to "Verwendungszweck", "counterparty" to "Empfänger", "iban" to "IBAN", "bookingText" to "Transaktionstyp", "eref" to "end_to_end_id", "category" to "Kategorie"),
        ),
    )

    data class CsvMapping(val name: String, val map: Map<String, String>)

    /** Auto-detect a known CSV mapping from its header, or null (→ manual mapping). */
    fun detectCsvMapping(header: List<String>?): CsvMapping? {
        val hset = (header ?: emptyList()).map { it.trim() }.toSet()
        for (k in KNOWN_CSV) {
            if (k.needs.all { it in hset }) {
                val map = k.map.filterValues { it in hset }
                return CsvMapping(k.name, map)
            }
        }
        return null
    }

    data class CsvApplied(val transactions: List<ParsedTx>, val skipped: Int)

    /** Apply a {field: columnName} mapping to parsed rows; rows without a date/amount are skipped. */
    fun applyCsvMapping(header: List<String>, rows: List<List<String>>, map: Map<String, String>): CsvApplied {
        val idx = HashMap<String, Int>()
        for ((field, col) in map) { val i = header.indexOf(col); if (i >= 0) idx[field] = i }
        fun get(row: List<String>, field: String): String = idx[field]?.let { row.getOrNull(it) ?: "" } ?: ""
        val transactions = mutableListOf<ParsedTx>(); var skipped = 0
        for (row in rows) {
            if (row.isEmpty()) continue
            val date = parseDate(get(row, "date"))
            val amount = parseAmount(get(row, "amount"))
            if (date == null || amount == null) { skipped++; continue }
            val purposeRaw = get(row, "purpose")
            val svwz = Regex("SVWZ\\+(.*?)(?:EREF\\+|MREF\\+|CRED\\+|$)", RegexOption.IGNORE_CASE).find(purposeRaw)
            transactions.add(
                ParsedTx(
                    date = date,
                    valueDate = parseDate(get(row, "valueDate")) ?: date,
                    amount = amount,
                    currency = get(row, "currency").trim().ifEmpty { "EUR" },
                    purpose = (svwz?.groupValues?.get(1) ?: purposeRaw).replace(Regex("\\s+"), " ").trim(),
                    counterparty = get(row, "counterparty").replace(Regex("\\s+"), " ").trim(),
                    iban = get(row, "iban").trim(),
                    bic = get(row, "bic").trim(),
                    bookingText = get(row, "bookingText").trim(),
                    eref = get(row, "eref").trim().ifEmpty { Regex("EREF\\+(\\S+)", RegexOption.IGNORE_CASE).find(purposeRaw)?.groupValues?.get(1) ?: "" },
                    category = get(row, "category").trim(),
                ),
            )
        }
        return CsvApplied(transactions, skipped)
    }

    // ---- dedup / enrich ----

    /** A stable signature for a transaction (dedup on re-import). */
    fun txSignature(tx: ParsedTx): String =
        if (tx.eref.isNotEmpty()) "e:${tx.eref}|${tx.amount}"
        else listOf(tx.date, "%.2f".format(java.util.Locale.ROOT, tx.amount), tx.counterparty.lowercase(), tx.purpose.take(40).lowercase()).joinToString("|")

    /** Only the incoming transactions not already present in [existing]. */
    fun dedupeTransactions(existing: List<ParsedTx>, incoming: List<ParsedTx>): List<ParsedTx> {
        val seen = existing.map(::txSignature).toMutableSet()
        val fresh = mutableListOf<ParsedTx>()
        for (tx in incoming) {
            val sig = txSignature(tx)
            if (sig in seen) continue
            seen.add(sig); fresh.add(tx)
        }
        return fresh
    }

    // Fields a later import may fill in on an already-known transaction.
    private val ENRICH_FIELDS = listOf("iban", "bic", "counterparty", "purpose", "bookingText", "eref", "valueDate")

    private fun fieldOf(tx: ParsedTx, f: String): String = when (f) {
        "iban" -> tx.iban; "bic" -> tx.bic; "counterparty" -> tx.counterparty; "purpose" -> tx.purpose
        "bookingText" -> tx.bookingText; "eref" -> tx.eref; "valueDate" -> tx.valueDate; else -> ""
    }

    data class TxUpdate(val sig: String, val patch: Map<String, String>)
    data class Enriched(val fresh: List<ParsedTx>, val updates: List<TxUpdate>)

    /**
     * Split incoming transactions against [existing]: brand-new ones (`fresh`), and ones that already
     * exist but carry info the stored record was missing (`updates`, each a { sig, patch } of only the
     * previously-empty fields). A re-import thus enriches instead of silently dropping the row.
     */
    fun enrichExisting(existing: List<ParsedTx>, incoming: List<ParsedTx>): Enriched {
        val bySig = existing.associateBy(::txSignature)
        val usedSig = mutableSetOf<String>()
        val fresh = mutableListOf<ParsedTx>(); val updates = mutableListOf<TxUpdate>()
        for (tx in incoming) {
            val sig = txSignature(tx)
            val match = bySig[sig]
            if (match == null) {
                if (sig !in usedSig) { usedSig.add(sig); fresh.add(tx) }
                continue
            }
            val patch = LinkedHashMap<String, String>()
            for (f in ENRICH_FIELDS) {
                if (fieldOf(match, f).trim().isEmpty() && fieldOf(tx, f).trim().isNotEmpty()) patch[f] = fieldOf(tx, f)
            }
            if (patch.isNotEmpty()) updates.add(TxUpdate(sig, patch))
        }
        return Enriched(fresh, updates)
    }

    // ---- payment-type classification ----
    private val TYPE_RULES = listOf(
        "card" to Regex("karten|sepa-elv|(?:^|[^a-z])elv|point of sale|\\bpos\\b|debitk|girocard|visa|mastercard", RegexOption.IGNORE_CASE),
        "debit" to Regex("lastschrift|einzug|abbuchung|abschlag|direct ?debit", RegexOption.IGNORE_CASE),
        "credit" to Regex("gutschr|gehalt|lohn|rente|zahlungseingang", RegexOption.IGNORE_CASE),
        "standingorder" to Regex("dauerauftr|standing ?order", RegexOption.IGNORE_CASE),
        "fee" to Regex("entgelt|gebühr|geb\\.|kontoführ", RegexOption.IGNORE_CASE),
        "transfer" to Regex("überweis|ueberweis|echtzeit|transfer|zahlung an", RegexOption.IGNORE_CASE),
    )

    /** Classify a transaction's payment type from its booking text/purpose. */
    fun classifyTxType(tx: ParsedTx): String {
        val s = "${tx.bookingText} ${tx.purpose}"
        for ((type, re) in TYPE_RULES) if (re.containsMatchIn(s)) return type
        return if (tx.amount >= 0) "credit" else "other"
    }
}
