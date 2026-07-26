package de.ledgerline.app.core.contacts

import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.LabeledValue
import de.ledgerline.app.domain.model.PostalAddress
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * vCard 4.0 (RFC 6350) import/export for [Contact]s, byte-compatible with the web
 * (`contacts.js`). Unknown properties are preserved through `_x` so a round-trip loses nothing.
 * Pure (no Android/network) → unit-testable. The avatar PHOTO is a separate encrypted blob and is
 * NOT inlined here (export omits it; import ignores an inline photo — the contact still imports).
 */
object VCard {

    // ---- Export ---------------------------------------------------------------

    fun export(contacts: List<Contact>): String = buildString {
        for (c in contacts) append(build(c))
    }

    private fun build(c: Contact): String {
        val lines = ArrayList<String>()
        fun add(line: String) = lines.add(fold(line))
        add("BEGIN:VCARD")
        add("VERSION:4.0")
        if (!c.uid.isNullOrBlank()) add("UID:" + c.uid)
        val fn = c.fn.ifBlank { listOf(c.prefix, c.first, c.middle, c.last, c.suffix).filter { it.isNotBlank() }.joinToString(" ") }
        add("FN:" + esc(fn))
        add("N:" + listOf(c.last, c.first, c.middle, c.prefix, c.suffix).joinToString(";") { esc(it) })
        if (c.nickname.isNotBlank()) add("NICKNAME:" + esc(c.nickname))
        if (c.org.isNotBlank() || c.department.isNotBlank()) add("ORG:" + esc(c.org) + (if (c.department.isNotBlank()) ";" + esc(c.department) else ""))
        if (c.title.isNotBlank()) add("TITLE:" + esc(c.title))
        if (c.role.isNotBlank()) add("ROLE:" + esc(c.role))
        if (c.vatId.isNotBlank()) add("X-VAT-ID:" + esc(c.vatId))
        for (e in c.emails) if (e.value.isNotBlank()) add("EMAIL;TYPE=" + e.type.ifBlank { "home" } + ":" + esc(e.value))
        for (p in c.phones) if (p.value.isNotBlank()) add("TEL;TYPE=" + p.type.ifBlank { "cell" } + ":" + esc(p.value))
        for (m in c.impp) if (m.value.isNotBlank()) add("IMPP;TYPE=" + m.type.ifBlank { "home" } + ":" + esc(m.value))
        for (a in c.addresses) add("ADR;TYPE=" + a.type.ifBlank { "home" } + ":;;" + esc(a.street) + ";" + esc(a.city) + ";" + esc(a.region) + ";" + esc(a.zip) + ";" + esc(a.country))
        for (u in c.urls) if (u.value.isNotBlank()) add("URL:" + esc(u.value))
        if (c.bday.isNotBlank()) add("BDAY:" + dateOut(c.bday))
        if (c.anniversary.isNotBlank()) add("ANNIVERSARY:" + dateOut(c.anniversary))
        if (c.note.isNotBlank()) add("NOTE:" + esc(c.note))
        if (c.categories.isNotEmpty()) add("CATEGORIES:" + c.categories.joinToString(",") { esc(it) })
        for (x in c._x) (x as? JsonPrimitive)?.content?.let { add(it) } // pass-through unknown props
        add("END:VCARD")
        return lines.joinToString("\r\n") + "\r\n"
    }

    // ---- Import ---------------------------------------------------------------

    fun parse(text: String): List<Contact> {
        val cards = ArrayList<Contact>()
        // RFC 6350 unfolding: a continuation line begins with a space or tab.
        val rfc = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\\n[ \\t]"), "")
        val lines = rfc.split("\n")
        var cur: Builder? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val up = line.uppercase()
            if (up == "BEGIN:VCARD") { cur = Builder(); i++; continue }
            if (up == "END:VCARD") { cur?.let { cards.add(it.toContact()) }; cur = null; i++; continue }
            val c = cur
            if (c == null) { i++; continue }
            val idx = vColon(line)
            if (idx < 0) { i++; continue }
            val left = line.substring(0, idx)
            val value = line.substring(idx + 1)
            val parts = left.split(";")
            val name = parts[0].substringAfterLast('.').uppercase() // strip Apple item1. group prefix
            val params = HashMap<String, String>()
            for (p in parts.drop(1)) {
                val eq = p.indexOf('=')
                if (eq < 0) params[p.uppercase()] = "" else params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).removeSurrounding("\"").uppercase()
            }
            val type = params["TYPE"]?.lowercase() ?: ""
            when (name) {
                "UID" -> c.uid = value
                "FN" -> c.fn = unesc(value)
                "N" -> value.split(";").map { unesc(it) }.let { f ->
                    c.last = f.getOrElse(0) { "" }; c.first = f.getOrElse(1) { "" }; c.middle = f.getOrElse(2) { "" }
                    c.prefix = f.getOrElse(3) { "" }; c.suffix = f.getOrElse(4) { "" }
                }
                "NICKNAME" -> c.nickname = unesc(value)
                "ORG" -> value.split(";").map { unesc(it) }.let { o -> c.org = o.getOrElse(0) { "" }; c.department = o.drop(1).filter { it.isNotBlank() }.joinToString(", ") }
                "TITLE" -> c.title = unesc(value)
                "ROLE" -> c.role = unesc(value)
                "EMAIL" -> c.emails.add(LabeledValue(unesc(value), normType(type, "home")))
                "TEL" -> c.phones.add(LabeledValue(unesc(value), normType(type, "cell")))
                "IMPP" -> c.impp.add(LabeledValue(unesc(value), normType(type, "home")))
                "URL" -> c.urls.add(LabeledValue(unesc(value), normType(type, "home")))
                "ADR" -> value.split(";").map { unesc(it) }.let { f ->
                    c.addresses.add(PostalAddress(f.getOrElse(2) { "" }, f.getOrElse(3) { "" }, f.getOrElse(4) { "" }, f.getOrElse(5) { "" }, f.getOrElse(6) { "" }, normType(type, "home")))
                }
                "BDAY" -> c.bday = dateIn(value)
                "ANNIVERSARY" -> c.anniversary = dateIn(value)
                "NOTE" -> c.note = unesc(value)
                "CATEGORIES" -> c.categories = value.split(",").map { unesc(it.trim()) }.filter { it.isNotBlank() }
                "X-VAT-ID", "X-VAT", "X-VATIN" -> c.vatId = unesc(value)
                "PHOTO", "VERSION", "PRODID", "REV" -> {} // avatar is a blob; version/rev regenerated
                else -> c.x.add(JsonPrimitive(line)) // preserve anything we don't model
            }
            i++
        }
        return cards
    }

    private class Builder {
        var uid: String? = null
        var fn = ""; var first = ""; var last = ""; var middle = ""; var prefix = ""; var suffix = ""
        var nickname = ""; var org = ""; var department = ""; var title = ""; var role = ""; var vatId = ""
        var bday = ""; var anniversary = ""; var note = ""
        val emails = ArrayList<LabeledValue>(); val phones = ArrayList<LabeledValue>()
        val impp = ArrayList<LabeledValue>(); val urls = ArrayList<LabeledValue>()
        val addresses = ArrayList<PostalAddress>()
        var categories = emptyList<String>()
        val x = ArrayList<JsonElement>()

        fun toContact() = Contact(
            uid = uid, fn = fn, first = first, last = last, middle = middle, prefix = prefix, suffix = suffix,
            nickname = nickname, org = org, department = department, title = title, role = role, vatId = vatId,
            emails = emails, phones = phones, impp = impp, urls = urls, addresses = addresses,
            bday = bday, anniversary = anniversary, note = note, categories = categories, _x = x,
        )
    }

    // ---- helpers (byte-exact to web) ------------------------------------------

    private fun esc(v: String): String =
        v.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private fun unesc(v: String): String =
        v.replace(Regex("\\\\n", RegexOption.IGNORE_CASE), "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** RFC 6350 §3.2: fold at 75 OCTETS (UTF-8), continuation lines start with a single space. */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var bytes = 0
        for (ch in line) {
            val b = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + b > 75) { out.add(cur.toString()); cur.setLength(0); cur.append(' '); bytes = 1 }
            cur.append(ch); bytes += b
        }
        out.add(cur.toString())
        return out.joinToString("\r\n")
    }

    private fun dateOut(d: String): String = d.replace("-", "")
    private fun dateIn(v: String): String {
        val s = v.filter { it.isDigit() }
        return if (s.length >= 8) "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}" else ""
    }

    private fun normType(raw: String, fallback: String): String {
        val toks = raw.lowercase().split(Regex("[,;]")).map { it.trim() }
        return when {
            toks.any { it == "cell" || it == "mobile" } -> "cell"
            toks.contains("work") -> "work"
            toks.contains("home") -> "home"
            else -> fallback
        }
    }

    /** Index of the first colon outside a double-quoted parameter value, or -1. */
    private fun vColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') quoted = !quoted else if (ch == ':' && !quoted) return i
        }
        return -1
    }
}
