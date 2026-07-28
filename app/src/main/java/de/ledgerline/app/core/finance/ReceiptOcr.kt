package de.ledgerline.app.core.finance

import kotlin.math.roundToLong

/**
 * Pattern recognition over a receipt's OCR/text — a port of the web `shared/receipt-ocr.js`. Pure +
 * testable, German-first. The **text comes from the server OCR endpoint** (`POST /invoices/ocr`); the
 * recognition stays client-side so it's identical across web/iOS/Android. Line structure matters: the
 * total/merchant/VAT recognisers read *labelled lines*, so the OCR text must preserve `\n`.
 */
object ReceiptOcr {

    private fun ci(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val CATEGORY_RULES: List<Pair<String, Regex>> = listOf(
        "Telekommunikation" to ci("\\btelekom\\b|vodafone|\\bo2\\b|1&1|mobilfunk|\\bdsl\\b|glasfaser|prepaid|magenta"),
        "Reisekosten" to ci("\\bhotel\\b|[üu]bernachtung|pension|hostel|deutsche bahn|\\bflug\\b|airline|lufthansa|ryanair|\\btaxi\\b|mietwagen|boarding|\\bbahncard\\b"),
        "Kfz" to ci("tankstelle|\\baral\\b|\\bshell\\b|\\besso\\b|\\bagip\\b|\\bomv\\b|diesel|benzin|kraftstoff|\\bkfz\\b|werkstatt|\\badac\\b"),
        "Bürobedarf" to ci("b[üu]robedarf|staples|schreibwaren|toner|druckerpatrone|kugelschreiber"),
        "Software" to ci("\\bsoftware\\b|lizenz|licen[sc]e|subscription|\\bsaas\\b|\\badobe\\b|microsoft|github|jetbrains|\\bfigma\\b|\\bslack\\b|\\bzoom\\b|google one|google workspace|google drive|google cloud|dropbox|\\bnotion\\b|atlassian|openai|anthropic|icloud|netcup|hetzner|ionos|strato|\\bovh\\b|contabo|digitalocean|linode|vultr|cloudflare|njalla|namecheap|godaddy|mullvad|\\bproton(mail| ag|\\.me)?\\b|tutao|tutanota|hosting|webspace|vserver|\\bvps\\b|\\bdomain\\b|\\bvpn\\b|wyze|backblaze|\\bageras\\b"),
        "Hardware" to ci("media\\s?markt|\\bsaturn\\b|notebook|\\blaptop\\b|\\bmonitor\\b|tastatur|festplatte|\\bssd\\b|conrad|reichelt"),
        "Marketing" to ci("\\bwerbung\\b|google ads|facebook ads|meta platforms|\\bkampagne\\b"),
        "Versicherung" to ci("versicherung|\\ballianz\\b|\\baxa\\b|\\bhuk\\b|\\bpolice\\b"),
        "Fortbildung" to ci("\\bseminar\\b|\\bschulung\\b|fortbildung|udemy|coursera|\\bkonferenz\\b|\\bworkshop\\b"),
        "Geschäftsessen" to ci("restaurant|gastst[äa]tte|pizzeria|trattoria|bistro|imbiss|\\bcaf[ée]\\b|\\bkaffee\\b|\\bbar\\b|brauhaus|wirtshaus|speisekarte|trinkgeld|bewirtung|mcdonald|\\bburger\\b|d[öo]ner"),
    )

    private fun amount(s: String): Double? {
        var t = s.replace(Regex("[^\\d.,]"), "")
        if (t.isEmpty()) return null
        val lc = t.lastIndexOf(','); val ld = t.lastIndexOf('.')
        t = when {
            lc > ld -> t.replace(".", "").replace(",", ".")
            ld > lc -> t.replace(",", "")
            else -> t.replace(",", ".")
        }
        val n = t.toDoubleOrNull() ?: return null
        return (n * 100.0).roundToLong() / 100.0
    }

    private val AMOUNTS_RE = ci("(\\d{1,3}(?:[.\\s]\\d{3})*[.,]\\d{2})|€\\s*(\\d{1,3}(?:[.\\s]\\d{3})*)(?![.,]\\d)|(\\d{1,3}(?:[.\\s]\\d{3})*)(?![.,]\\d)\\s*(?:€|eur\\b)")
    private fun amountsIn(line: String): List<Double> =
        AMOUNTS_RE.findAll(line).mapNotNull { m ->
            amount(m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } })
        }.toList()

    private val NET_LINE = ci("zwischensumme|zwischensal|nettosumme|nettobetrag|nettogesamt|netto-?summe|subtotal|\\bmwst\\b|umsatzsteuer|\\bust\\b|mehrwertsteuer|\\bvat\\b|sales tax")
    private val GROSS_LINE = ci("summe|gesamt|rechnungsbetrag|endbetrag|grand total|\\btotal\\b|amount paid|\\bpaid\\b|bezahlt|gezahlt|zu zahlen")

    /** The receipt total: the amount on a gross-total line, else the max amount seen. */
    fun extractTotal(text: String?): Double? {
        val lines = (text ?: "").split(Regex("\\r?\\n"))
        var labelled: Double? = null; var max: Double? = null
        for (ln in lines) {
            val vals = amountsIn(ln)
            if (vals.isEmpty()) continue
            for (v in vals) if (max == null || v > max!!) max = v
            if (NET_LINE.containsMatchIn(ln)) continue
            if (GROSS_LINE.containsMatchIn(ln)) {
                val v = vals.last()
                if (v != 0.0 && (labelled == null || v > labelled!!)) labelled = v
            }
        }
        return labelled ?: max
    }

    private val MONTHS = mapOf(
        "januar" to 1, "februar" to 2, "märz" to 3, "maerz" to 3, "april" to 4, "mai" to 5, "juni" to 6, "juli" to 7,
        "august" to 8, "september" to 9, "oktober" to 10, "november" to 11, "dezember" to 12,
        "january" to 1, "february" to 2, "march" to 3, "june" to 6, "july" to 7, "october" to 10, "december" to 12,
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9,
        "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "dez" to 12,
    )
    private fun okDate(y: Int, mo: Int, d: Int) = mo in 1..12 && d in 1..31 && y in 2000..2100
    private fun pad2(n: Int) = n.toString().padStart(2, '0')

    /** First plausible date → ISO yyyy-mm-dd, or "". */
    fun extractDate(text: String?): String {
        val s = text ?: ""
        for (mm in Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b").findAll(s)) {
            val y = if (mm.groupValues[3].length == 2) 2000 + mm.groupValues[3].toInt() else mm.groupValues[3].toInt()
            val d = mm.groupValues[1].toInt(); val mo = mm.groupValues[2].toInt()
            if (okDate(y, mo, d)) return "$y-${pad2(mo)}-${pad2(d)}"
        }
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(s)?.let {
            if (okDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()))
                return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}"
        }
        Regex("\\b(\\d{1,2})[.\\s-]+([A-Za-zäöüÄÖÜ]{3,})[.\\s-]+(\\d{4})\\b").find(s)?.let { m ->
            MONTHS[m.groupValues[2].lowercase()]?.let { return "${m.groupValues[3]}-${pad2(it)}-${m.groupValues[1].padStart(2, '0')}" }
        }
        Regex("\\b([A-Za-zäöüÄÖÜ]+)\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})\\b").find(s)?.let { m ->
            MONTHS[m.groupValues[1].lowercase()]?.let { return "${m.groupValues[3]}-${pad2(it)}-${m.groupValues[2].padStart(2, '0')}" }
        }
        return ""
    }

    private val MERCHANT_SKIP = ci("^(ihre|ihr\\b|your|rechnung|invoice|beleg|quittung|gutschrift|credit ?note|datum|date|kunde|customer|seite|page|betreff|subject|from\\b|bill ?to|ship ?to|paid\\b|vat\\b|ust|steuer|item|menge|position|betrag|summe|total|details|leistungen|verkauft|sold by|umsatzsteuer|payment|sequenz|order\\b|bestell)")
    private val COMPANY_SUFFIX = ci("\\b(gmbh|mbh|ug|ag|kg|ohg|gbr|ltd|limited|llc|inc|corp|b\\.?v\\.?|s\\.?[àa]\\.?r\\.?l|s\\.?a\\.?|ab|oy|llp|plc)\\b|& co")
    private val DESPACE_RE = Regex("(?:\\b[A-Za-zÄÖÜäöü] ){2,}\\b[A-Za-zÄÖÜäöü]\\b")
    private fun despace(s: String) = DESPACE_RE.replace(s) { it.value.replace(" ", "") }

    private fun cleanMerchant(l: String): String =
        despace(l).split(Regex("\\s*[|•·]\\s*"))[0]
            .replace(ci("\\s*(bill|ship)\\s*to\\b.*$"), "")
            .replace(ci("\\s+(place\\s*/?\\s*date|place of invoice|date of invoice|invoice (requested|number|date|no)\\b|customer\\b|kundennummer\\b).*$"), "")
            .replace(ci(",?\\s*(pf\\b|postfach|\\d|[^,]*(?:stra(?:ß|ss)e|str\\.|weg|ring|platz|allee|gasse)\\b).*$"), "")
            .replace(ci("\\s+(invoice|rechnung|receipt|quittung|beleg)\\s*$"), "")
            .replace(Regex("\\s{2,}"), " ").trim().take(50)

    private val BRANDS: List<Pair<String, Regex>> = listOf(
        "Amazon" to ci("\\bamazon\\b"), "Apple" to ci("\\bapple\\b"), "Google" to ci("google"), "PayPal" to ci("paypal"),
        "Backblaze" to ci("backblaze"), "Microsoft" to ci("microsoft"), "Netflix" to ci("netflix"), "Spotify" to ci("spotify"),
        "eBay" to ci("\\bebay\\b"), "Dropbox" to ci("dropbox"), "Cloudflare" to ci("cloudflare"), "Adobe" to ci("\\badobe\\b"),
        "DeepL" to ci("\\bdeepl\\b"), "Telekom" to ci("\\btelekom\\b|magenta"), "Vodafone" to ci("vodafone"),
        "Kaufland" to ci("kaufland"), "Edeka" to ci("\\bedeka\\b"), "REWE" to ci("\\brewe\\b"), "Lidl" to ci("\\blidl\\b"), "Aldi" to ci("\\baldi\\b"),
        "IKEA" to ci("\\bikea\\b"), "Deutsche Bahn" to ci("deutsche bahn|\\bbahn\\.de\\b"), "Hetzner" to ci("hetzner"), "netcup" to ci("netcup"),
    )
    fun detectBrand(text: String?): String { val s = text ?: ""; for ((n, re) in BRANDS) if (re.containsMatchIn(s)) return n; return "" }

    /** The merchant/seller name (company-legal-form line, then a known brand, then first meaningful line). */
    fun extractMerchant(text: String?): String {
        val lines = (text ?: "").split(Regex("\\r?\\n")).map { it.replace(Regex("\\s{2,}"), " ").trim() }.filter { it.isNotEmpty() }
        for (l in lines.take(15)) {
            if (l.length < 3 || MERCHANT_SKIP.containsMatchIn(l) || !COMPANY_SUFFIX.containsMatchIn(l)) continue
            val c = cleanMerchant(l)
            if (c.length in 3..50) return c
        }
        detectBrand(text).takeIf { it.isNotEmpty() }?.let { return it }
        for (l in lines.take(8)) {
            if (l.length < 3 || l.length > 42) continue
            if (Regex("^\\d").containsMatchIn(l) || Regex("\\d{2}[.:]\\d{2}").containsMatchIn(l) || ci("www\\.|http|@|steuer|ust-?id|tel\\.?:").containsMatchIn(l)) continue
            if (MERCHANT_SKIP.containsMatchIn(l) || !ci("[a-zäöüß]").containsMatchIn(l)) continue
            return cleanMerchant(l)
        }
        return ""
    }

    private val NUMBER_RE = ci("(?:rechnungs?\\s*-?\\s*(?:nr|nummer)|invoice\\s*(?:no|number|#)|beleg\\s*-?\\s*nr|rg\\s*-?\\s*nr|receipt\\s*(?:no|number))\\.?\\s*[:#]?\\s*([A-Za-z]?[0-9][A-Za-z0-9./-]{1,24})")
    fun extractNumber(text: String?): String {
        val m = NUMBER_RE.find(text ?: "") ?: return ""
        val t = m.groupValues[1].replace(Regex("[.,;:]+$"), "")
        if (Regex("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}$").matches(t)) return ""
        if (Regex("^\\d{1,2}[./-][A-Za-z]{3,}[./-]\\d{2,4}$").matches(t)) return ""
        if (t.replace(Regex("[^A-Za-z0-9]"), "").length < 3) return ""
        return t
    }

    /** The document VAT rate → "19" | "16" | "7" | "0" | "" (highest explicit rate wins). */
    fun extractVatRate(text: String?): String {
        val s = text ?: ""
        if (ci("kleinunternehmer|§\\s?19\\s?ust|steuerfrei|reverse[-\\s]?charge|nicht steuerbar|tax[-\\s]?free").containsMatchIn(s)) return "0"
        val rates = HashSet<String>()
        for (ln in s.split(Regex("\\r?\\n"))) {
            if (!ci("mwst|ust\\b|u\\.?st\\.?|umsatzsteuer|\\bvat\\b|\\btax\\b|zzgl|steuer").containsMatchIn(ln)) continue
            for (m in Regex("\\b(\\d{1,2})(?:[.,]\\d+)?\\s*%").findAll(ln)) if (m.groupValues[1] in setOf("19", "16", "7")) rates.add(m.groupValues[1])
        }
        return if ("19" in rates) "19" else if ("16" in rates) "16" else if ("7" in rates) "7" else ""
    }

    /** The document currency → "USD" | "GBP" | "CHF" | "EUR" | "". */
    fun extractCurrency(text: String?): String {
        val s = text ?: ""
        val hasEur = Regex("\\bEUR\\b|€").containsMatchIn(s)
        if (Regex("\\bUSD\\b|US\\$").containsMatchIn(s)) return "USD"
        if (Regex("\\bGBP\\b|£\\s?\\d").containsMatchIn(s)) return "GBP"
        if (Regex("\\bCHF\\b").containsMatchIn(s)) return "CHF"
        if (hasEur) return "EUR"
        if (Regex("\\$\\s?\\d").containsMatchIn(s)) return "USD"
        return ""
    }

    data class Analysis(
        val merchant: String, val category: String, val total: Double?, val date: String,
        val number: String, val vat: String, val currency: String, val tags: List<String>,
    )

    /** Analyse a receipt's OCR text into structured booking fields (web `analyzeReceiptText`). */
    fun analyze(text: String?): Analysis {
        val low = (text ?: "").lowercase()
        val category = CATEGORY_RULES.firstOrNull { it.second.containsMatchIn(low) }?.first ?: ""
        val merchant = extractMerchant(text)
        val tags = LinkedHashSet(listOf(merchant, category).filter { it.isNotEmpty() }).toList()
        return Analysis(merchant, category, extractTotal(text), extractDate(text), extractNumber(text), extractVatRate(text), extractCurrency(text), tags)
    }
}
