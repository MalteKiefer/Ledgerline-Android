package de.ledgerline.app.core.passkey

import de.ledgerline.app.domain.model.SecretItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-app management of passkeys embedded in a login item (list + delete). */
class PasskeyManagementTest {
    private fun pk(cred: String, rp: String, user: String) = buildJsonObject {
        put("credentialId", cred); put("rpId", rp); put("userName", user); put("createdAt", "2026-01-01")
    }

    private fun login(vararg passkeys: JsonObject) = SecretItem(
        id = "L1", type = "login",
        fields = buildJsonObject {
            put("username", "u")
            put("passkeys", JsonArray(passkeys.toList()))
            put("keep", "me")
        },
    )

    @Test fun embedded_lists_each_passkey() {
        val refs = PasskeyStore.embedded(login(pk("c1", "a.com", "alice"), pk("c2", "b.com", "bob")))
        assertEquals(listOf("c1" to "alice", "c2" to "bob"), refs.map { it.credentialIdB64 to it.userName })
        assertEquals("a.com", refs.first().rpId)
    }

    @Test fun embedded_is_empty_for_non_login() {
        assertTrue(PasskeyStore.embedded(SecretItem(id = "x", type = "password")).isEmpty())
    }

    @Test fun detach_removes_one_and_preserves_other_fields() {
        val items = listOf(login(pk("c1", "a.com", "alice"), pk("c2", "b.com", "bob")))
        val out = PasskeyStore.detach("L1", "c1", items, "2026-02-02")
        val fields = out.single().fields
        assertEquals(listOf("c2"), PasskeyStore.embedded(out.single()).map { it.credentialIdB64 })
        assertEquals("me", fields["keep"]?.jsonPrimitive?.content) // unknown field preserved
        assertEquals("2026-02-02", out.single().updated)
    }

    @Test fun detach_last_passkey_drops_the_key_entirely() {
        val out = PasskeyStore.detach("L1", "c1", listOf(login(pk("c1", "a.com", "alice"))), "now")
        assertFalse(out.single().fields.containsKey("passkeys"))
    }

    @Test fun detach_ignores_other_items() {
        val other = SecretItem(id = "L2", type = "login", fields = buildJsonObject { put("passkeys", buildJsonArray { add(pk("c9", "z.com", "z")) }) })
        val out = PasskeyStore.detach("L1", "c9", listOf(other), "now")
        assertEquals(1, PasskeyStore.embedded(out.single()).size) // untouched
    }
}
