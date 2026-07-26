package de.ledgerline.app.core.contacts

import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.LabeledValue
import de.ledgerline.app.domain.model.PostalAddress
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {

    @Test fun export_has_vcard4_structure() {
        val c = Contact(fn = "Jane Doe", first = "Jane", last = "Doe", org = "ACME", vatId = "DE123",
            emails = listOf(LabeledValue("jane@x.com", "work")), phones = listOf(LabeledValue("+49 1", "cell")))
        val out = VCard.export(listOf(c))
        assertTrue(out.startsWith("BEGIN:VCARD\r\nVERSION:4.0\r\n"))
        assertTrue(out.contains("FN:Jane Doe\r\n"))
        assertTrue(out.contains("N:Doe;Jane;;;\r\n"))
        assertTrue(out.contains("EMAIL;TYPE=work:jane@x.com\r\n"))
        assertTrue(out.contains("TEL;TYPE=cell:+49 1\r\n"))
        assertTrue(out.contains("X-VAT-ID:DE123\r\n"))
        assertTrue(out.endsWith("END:VCARD\r\n"))
    }

    @Test fun round_trips_all_modeled_fields() {
        val c = Contact(
            fn = "Jane Q. Doe", first = "Jane", last = "Doe", middle = "Q", nickname = "JD",
            org = "ACME", department = "R&D", title = "Eng", role = "Dev", vatId = "DE9",
            emails = listOf(LabeledValue("j@x.com", "home")), phones = listOf(LabeledValue("123", "work")),
            addresses = listOf(PostalAddress("Main 1", "Town", "St", "12345", "DE", "home")),
            bday = "1990-05-01", note = "hi; there, ok", categories = listOf("a", "b"),
        )
        val parsed = VCard.parse(VCard.export(listOf(c)))
        assertEquals(1, parsed.size)
        val p = parsed[0]
        assertEquals("Jane Q. Doe", p.fn); assertEquals("Q", p.middle); assertEquals("R&D", p.department)
        assertEquals("DE9", p.vatId); assertEquals("1990-05-01", p.bday)
        assertEquals("hi; there, ok", p.note) // escaped ; and , survive
        assertEquals(listOf("a", "b"), p.categories)
        assertEquals("12345", p.addresses[0].zip); assertEquals("Town", p.addresses[0].city)
    }

    @Test fun unknown_property_preserved_via_x() {
        val vcf = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob\r\nX-CUSTOM-THING:keepme\r\nEND:VCARD\r\n"
        val p = VCard.parse(vcf).single()
        assertTrue(p._x.any { (it as? JsonPrimitive)?.content == "X-CUSTOM-THING:keepme" })
        // and it re-emits on export
        assertTrue(VCard.export(listOf(p)).contains("X-CUSTOM-THING:keepme\r\n"))
    }

    @Test fun parses_multiple_and_folded_lines() {
        val vcf = "BEGIN:VCARD\nVERSION:4.0\nFN:A\nEND:VCARD\nBEGIN:VCARD\nVERSION:4.0\nFN:B\nEND:VCARD\n"
        assertEquals(listOf("A", "B"), VCard.parse(vcf).map { it.fn })
    }

    @Test fun tel_type_normalisation() {
        val vcf = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:X\r\nTEL;TYPE=MOBILE:5\r\nEND:VCARD\r\n"
        assertEquals("cell", VCard.parse(vcf).single().phones.single().type)
    }
}
