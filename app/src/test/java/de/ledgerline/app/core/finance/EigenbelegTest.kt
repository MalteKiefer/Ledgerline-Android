package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Transaction
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EigenbelegTest {
    @Test fun net_and_vat_split_from_gross() {
        val d = Eigenbeleg.Draft(gross = 119.0, vatRate = 19.0)
        assertEquals(100.0, d.net, 0.001)
        assertEquals(19.0, d.vat, 0.001)
    }

    @Test fun prefill_guesses_grund_from_sign_and_vatcat() {
        val out = Transaction(id = "t", account = "a", date = "2026-07-01", amount = -50.0, vatCat = "private")
        assertEquals("privatentnahme", Eigenbeleg.prefill(out, "Me", "Berlin", 19.0, "2026-07-30").grund)
        val inc = out.copy(amount = 50.0)
        assertEquals("privateinlage", Eigenbeleg.prefill(inc, "Me", "Berlin", 19.0, "2026-07-30").grund)
        val biz = out.copy(vatCat = "19")
        assertEquals("betriebsausgabe", Eigenbeleg.prefill(biz, "Me", "Berlin", 19.0, "2026-07-30").grund)
    }

    @Test fun business_expense_requires_recipient_and_reason() {
        val base = Eigenbeleg.Draft(grund = "betriebsausgabe", gross = 10.0)
        assertFalse(base.valid)
        assertTrue(base.copy(recipient = "Shop", reason = "lost").valid)
        // A private draw only needs an amount.
        assertTrue(Eigenbeleg.Draft(grund = "privatentnahme", gross = 10.0).valid)
    }

    @Test fun receipt_carries_kind_and_snapshot() {
        val d = Eigenbeleg.Draft(grund = "trinkgeld", gross = 5.0, vatRate = 0.0)
        val r = Eigenbeleg.receipt("id1", "eg.pdf", "blob1", "key1", "sig1", d)
        assertEquals("eigenbeleg", r.kind)
        assertTrue(r.isEigenbeleg)
        assertEquals("eigenbeleg", r.raw["kind"]!!.jsonPrimitive.content)
        assertEquals("trinkgeld", (r.raw["eigenbeleg"] as kotlinx.serialization.json.JsonObject)["grund"]!!.jsonPrimitive.content)
    }
}
