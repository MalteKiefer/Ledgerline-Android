package de.ledgerline.app.core.finance

import de.ledgerline.app.core.finance.BankStatement.ParsedTx
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceCustomer
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.InvoiceStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceMatchTest {

    private fun inv(id: String, number: String?, status: InvoiceStatus, customer: String, gross: Double, paymentTxId: String? = null) =
        Invoice(
            id = id, number = number, status = status,
            customer = InvoiceCustomer(name = customer),
            lines = listOf(InvoiceLine(desc = "x", qty = 1.0, unitPrice = gross, vatRate = 0.0)),
            raw = if (paymentTxId != null) JsonObject(mapOf("paymentTxId" to JsonPrimitive(paymentTxId))) else JsonObject(emptyMap()),
        )

    private val invoices = listOf(
        inv("a", "2026-0001", InvoiceStatus.SENT, "ACME GmbH", 1071.0),
        inv("b", "2026-0002", InvoiceStatus.SENT, "Globex", 500.0),
        inv("c", "2026-0003", InvoiceStatus.PAID, "Initech", 500.0),          // same amount as b (ambiguous)
        inv("d", null, InvoiceStatus.DRAFT, "Draft Co", 1071.0),              // draft: never a candidate
        inv("e", "2026-0005", InvoiceStatus.SENT, "Linked", 999.0, paymentTxId = "tx-x"), // already linked
    )

    @Test fun matches_by_invoice_number_in_purpose() {
        val tx = ParsedTx(date = "2026-06-01", amount = 1071.0, purpose = "Zahlung Rechnung 2026-0001 danke")
        assertEquals("a", InvoiceMatch.matchInvoice(tx, invoices)?.id)
    }

    @Test fun matches_by_customer_name_when_amount_agrees() {
        val tx = ParsedTx(date = "2026-06-01", amount = 1071.0, counterparty = "ACME GmbH")
        assertEquals("a", InvoiceMatch.matchInvoice(tx, invoices)?.id)
    }

    @Test fun matches_unique_exact_amount() {
        val tx = ParsedTx(date = "2026-06-01", amount = 1071.0, purpose = "no hints")
        assertEquals("a", InvoiceMatch.matchInvoice(tx, invoices)?.id)   // only 'a' is 1071 among candidates
    }

    @Test fun ambiguous_amount_does_not_match() {
        val tx = ParsedTx(date = "2026-06-01", amount = 500.0, purpose = "no hints")
        // b (sent) and c (paid) both 500 and both candidates → not unique → null.
        assertNull(InvoiceMatch.matchInvoice(tx, invoices))
    }

    @Test fun negative_and_zero_amounts_never_match() {
        assertNull(InvoiceMatch.matchInvoice(ParsedTx(date = "x", amount = -1071.0, counterparty = "ACME GmbH"), invoices))
        assertNull(InvoiceMatch.matchInvoice(ParsedTx(date = "x", amount = 0.0), invoices))
    }

    @Test fun already_linked_and_draft_are_excluded() {
        // 999 only belongs to 'e' which is already linked → no match.
        assertNull(InvoiceMatch.matchInvoice(ParsedTx(date = "x", amount = 999.0, counterparty = "Linked"), invoices))
    }
}
