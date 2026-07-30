package de.ledgerline.app.core.offline

import de.ledgerline.app.data.SealTagCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncOutboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rec(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun coll(vararg recs: Pair<String, JsonObject>): Map<String, Map<String, JsonObject>> =
        mapOf("notes" to recs.toMap())

    @Test fun diff_captures_upserts_and_deletes() {
        val base = coll("a" to rec("t" to "A"), "b" to rec("t" to "B"))
        val local = coll("a" to rec("t" to "A2"), "c" to rec("t" to "C")) // a changed, b deleted, c added
        val d = StoreDelta.diff(base, local).collections["notes"]!!
        assertEquals(setOf("a", "c"), d.upserts.keys)
        assertEquals("A2", (d.upserts["a"]!!["t"] as JsonPrimitive).content)
        assertEquals(listOf("b"), d.deletes)
    }

    @Test fun compose_is_net_effect_of_two_saves() {
        val c = coll("a" to rec("t" to "A"), "b" to rec("t" to "B"))
        val e1 = coll("a" to rec("t" to "A1"), "b" to rec("t" to "B")) // edit a
        val e2 = coll("a" to rec("t" to "A2"))                          // edit a again, delete b
        val d1 = StoreDelta.diff(c, e1)
        val d2 = StoreDelta.diff(e1, e2)
        val composed = d1.then(d2).collections["notes"]!!
        val direct = StoreDelta.diff(c, e2).collections["notes"]!!
        assertEquals(direct.upserts, composed.upserts)
        assertEquals(direct.deletes.toSet(), composed.deletes.toSet())
        assertEquals("A2", (composed.upserts["a"]!!["t"] as JsonPrimitive).content)
        assertEquals(listOf("b"), composed.deletes)
    }

    @Test fun outbox_roundtrips_vk_sealed_and_composes_on_append() {
        val outbox = SyncOutbox(tmp.newFolder("outbox"), SealTagCrypto())
        val vk = ByteArray(32)
        assertNull(outbox.pending("passwords", vk))

        outbox.append("passwords", StoreDelta.diff(coll("a" to rec("t" to "A")), coll("a" to rec("t" to "A1"))), vk)
        outbox.append("passwords", StoreDelta.diff(coll("a" to rec("t" to "A1")), coll()), vk) // delete a
        assertTrue(outbox.hasPending())

        val pending = outbox.pending("passwords", vk)!!.collections["notes"]!!
        // Net effect of "upsert a then delete a" = just a delete.
        assertTrue(pending.upserts.isEmpty())
        assertEquals(listOf("a"), pending.deletes)

        outbox.clear("passwords")
        assertNull(outbox.pending("passwords", vk))
        assertTrue(!outbox.hasPending())
    }

    @Test fun sealed_at_rest_not_plaintext() {
        val root = tmp.newFolder("outbox")
        val outbox = SyncOutbox(root, SealTagCrypto())
        outbox.append("passwords", StoreDelta.diff(coll(), coll("secret1" to rec("password" to "hunter2"))), ByteArray(32))
        val onDisk = java.io.File(root, "passwords.json").readText()
        assertTrue("outbox file must be sealed", onDisk.startsWith("SEALED:"))
    }
}
