package de.ledgerline.app.data

import de.ledgerline.app.domain.model.Invoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: web sets `trashed` to an ISO timestamp on delete (`bool|str`). The codec must render
 *  it as `false | ISO`, NEVER a boolean — a naive `JsonPrimitive(bool)` destroyed a web delete
 *  timestamp on the first Android save (data loss). Mirrors FileRecordCodec/WorkspaceRecordCodec. */
class FinanceTrashedCodecTest {

    private fun invoiceJson(trashed: String) =
        Json.parseToJsonElement("""{"id":"i1","status":"draft","trashed":$trashed}""").jsonObject

    @Test fun preserves_web_iso_trashed_timestamp_on_resave() {
        val iso = "2026-07-20T09:30:00.000Z"
        val inv = FinanceRecordCodec.decodeInvoice(invoiceJson("\"$iso\""))!!
        assertTrue(inv.trashed) // decodes truthy
        val out = FinanceRecordCodec.encodeInvoice(inv)
        assertEquals(
            "web ISO delete timestamp must survive the Android round-trip, not collapse to a bool",
            JsonPrimitive(iso), out["trashed"],
        )
    }

    @Test fun android_trash_writes_iso_not_true() {
        // A live (untrashed) invoice that Android now trashes.
        val inv = FinanceRecordCodec.decodeInvoice(invoiceJson("false"))!!.copy(trashed = true)
        val out = FinanceRecordCodec.encodeInvoice(inv)
        val t = out["trashed"]
        assertTrue("expected an ISO string, got $t", t is JsonPrimitive && (t as JsonPrimitive).isString)
        assertTrue("must not be the literal boolean true", t.toString() != "true")
    }

    @Test fun android_restore_writes_null_not_false() {
        val inv = FinanceRecordCodec.decodeInvoice(invoiceJson("\"2026-01-01T00:00:00Z\""))!!.copy(trashed = false)
        val out = FinanceRecordCodec.encodeInvoice(inv)
        assertEquals(JsonNull, out["trashed"])
    }
}
