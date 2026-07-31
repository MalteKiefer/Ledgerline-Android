package de.ledgerline.app.data.finance

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FinanceOutboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun outbox() = FinanceOutbox(tmp.newFile("outbox-${System.nanoTime()}.json"))

    private fun body(v: Int, name: String) = buildJsonObject {
        put("version", JsonPrimitive(v)); put("name", JsonPrimitive(name))
    }

    @Test fun add_persist_roundtrip() {
        val f = tmp.newFile("ob.json")
        FinanceOutbox(f).add(FinanceOp("partner", "update", 5, body(1, "A")))
        // A fresh instance over the same file reads it back (durable).
        val ops = FinanceOutbox(f).all()
        assertEquals(1, ops.size)
        assertEquals("partner", ops[0].entity)
        assertEquals(5, ops[0].id)
        assertEquals("A", (ops[0].body!!["name"] as JsonPrimitive).content)
    }

    @Test fun coalesce_keeps_latest_per_record_and_delete_supersedes_update() {
        val ob = outbox()
        ob.addCoalesced(FinanceOp("invoice", "update", 1, body(1, "v1")))
        ob.addCoalesced(FinanceOp("invoice", "update", 1, body(2, "v2"))) // supersedes
        ob.addCoalesced(FinanceOp("partner", "update", 9, body(1, "P")))  // different record
        assertEquals(2, ob.all().size)
        val inv = ob.all().first { it.entity == "invoice" && it.id == 1 }
        assertEquals("v2", (inv.body!!["name"] as JsonPrimitive).content)

        ob.addCoalesced(FinanceOp("invoice", "delete", 1)) // delete drops the queued update
        val invOps = ob.all().filter { it.entity == "invoice" && it.id == 1 }
        assertEquals(1, invOps.size)
        assertEquals("delete", invOps[0].action)
    }

    @Test fun remove_and_clear() {
        val ob = outbox()
        val op = FinanceOp("project", "delete", 3)
        ob.add(op)
        assertTrue(!ob.isEmpty())
        ob.remove(op)
        assertTrue(ob.isEmpty())
    }
}
