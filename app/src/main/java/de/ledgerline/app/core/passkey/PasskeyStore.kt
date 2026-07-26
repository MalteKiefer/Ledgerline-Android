package de.ledgerline.app.core.passkey

import de.ledgerline.app.domain.model.SecretItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Resolves and creates passkeys in the zero-knowledge secrets manifest, byte-compatible with the
 * web (`passkey.js`) and iOS (`PasskeyStore.swift`). A passkey lives either as a standalone
 * `passkey` [SecretItem] (its `fields` = the passkey object) or embedded in a `login` item's
 * `fields["passkeys"]` array. Field shape:
 * `{rpId,rpName,credentialId(b64url),alg:-7,privateKey(JWK),publicKey(JWK),userHandle(b64url),
 *   userName,userDisplayName,signCount:0,createdAt}`.
 */
object PasskeyStore {

    /** A resolved passkey ready for assertion. */
    data class StoredPasskey(
        val rpId: String,
        val credentialId: ByteArray,
        val privateKeyJwk: String,
        val userHandle: ByteArray,
        val userName: String,
        val userDisplayName: String,
        val source: Source,
    )

    sealed interface Source {
        data class Standalone(val itemId: String) : Source
        data class Embedded(val loginId: String) : Source
    }

    /**
     * A syntactically acceptable relying-party id: non-empty, trimmed, at least one dot, no
     * leading/trailing dot, no empty labels. Defense-in-depth against cross-origin exposure.
     */
    fun rpIdAllowed(rpId: String): Boolean =
        rpId == rpId.trim() &&
            rpId.isNotEmpty() && rpId.contains('.') &&
            !rpId.startsWith('.') && !rpId.endsWith('.') && !rpId.contains("..")

    /**
     * Every passkey in [items] whose `rpId` matches [rpId], minus any whose `credentialId` is in
     * [excludeCredentialIds]. Scans standalone `passkey` items and each `login`'s embedded array.
     */
    fun candidates(rpId: String, items: List<SecretItem>, excludeCredentialIds: List<ByteArray> = emptyList()): List<StoredPasskey> {
        if (!rpIdAllowed(rpId)) return emptyList()
        val exclude = excludeCredentialIds.map { it.toList() }
        val out = ArrayList<StoredPasskey>()
        for (item in items) {
            when (item.type) {
                "passkey" -> parse(item.fields, Source.Standalone(item.id), rpId)
                    ?.takeIf { it.rpId == rpId && it.credentialId.toList() !in exclude }
                    ?.let(out::add)
                "login" -> (item.fields["passkeys"] as? JsonArray)?.forEach { entry ->
                    val obj = entry as? JsonObject ?: return@forEach
                    val storedRp = obj["rpId"]?.jsonPrimitive?.content
                    if (storedRp != null && storedRp == rpId) {
                        parse(obj, Source.Embedded(item.id), storedRp)
                            ?.takeIf { it.credentialId.toList() !in exclude }
                            ?.let(out::add)
                    }
                }
            }
        }
        return out
    }

    /** A new standalone `passkey` [SecretItem] (web field shape). [now] = ISO-8601 for created/updated. */
    fun standaloneItem(
        rpId: String, rpName: String, credentialId: ByteArray, privateKeyJwk: String, publicKeyJwk: String,
        userHandle: ByteArray, userName: String, userDisplayName: String, now: String,
    ): SecretItem = SecretItem(
        id = de.ledgerline.app.core.Ids.newId(),
        type = "passkey",
        title = rpName.ifBlank { rpId },
        fields = passkeyFields(rpId, rpName, credentialId, privateKeyJwk, publicKeyJwk, userHandle, userName, userDisplayName, now),
        created = now,
        updated = now,
    )

    /**
     * Append a passkey to the `login` [loginId]'s `fields["passkeys"]` array (with rpId embedded),
     * preserving other keys. Returns [items] with that login updated; malformed `fields` → unchanged.
     */
    fun attach(
        loginId: String, rpId: String, rpName: String, items: List<SecretItem>, credentialId: ByteArray,
        privateKeyJwk: String, publicKeyJwk: String, userHandle: ByteArray, userName: String, userDisplayName: String, now: String,
    ): List<SecretItem> {
        val entry = passkeyFields(rpId, rpName, credentialId, privateKeyJwk, publicKeyJwk, userHandle, userName, userDisplayName, now)
        return items.map { item ->
            if (item.id != loginId || item.type != "login") return@map item
            val existing = (item.fields["passkeys"] as? JsonArray)?.toList() ?: emptyList()
            val merged = JsonObject(item.fields + ("passkeys" to JsonArray(existing + entry)))
            item.copy(fields = merged, updated = now)
        }
    }

    /** A lightweight view of a passkey for in-app management (list + delete). */
    data class PasskeyRef(val credentialIdB64: String, val rpId: String, val userName: String, val createdAt: String?)

    /** The passkeys embedded in a `login` item's `fields["passkeys"]`, for display/management. */
    fun embedded(item: SecretItem): List<PasskeyRef> {
        if (item.type != "login") return emptyList()
        return (item.fields["passkeys"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val cid = o["credentialId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            PasskeyRef(
                credentialIdB64 = cid,
                rpId = o["rpId"]?.jsonPrimitive?.content ?: "",
                userName = o["userName"]?.jsonPrimitive?.content ?: "",
                createdAt = o["createdAt"]?.jsonPrimitive?.content,
            )
        } ?: emptyList()
    }

    /**
     * Remove the embedded passkey with [credentialIdB64] from the `login` [loginId], preserving
     * every other field; drops the `passkeys` key entirely when the last one is removed.
     */
    fun detach(loginId: String, credentialIdB64: String, items: List<SecretItem>, now: String): List<SecretItem> =
        items.map { item ->
            if (item.id != loginId || item.type != "login") return@map item
            val arr = item.fields["passkeys"] as? JsonArray ?: return@map item
            val kept = arr.filter { (it as? JsonObject)?.get("credentialId")?.jsonPrimitive?.content != credentialIdB64 }
            val newFields = if (kept.isEmpty()) JsonObject(item.fields - "passkeys") else JsonObject(item.fields + ("passkeys" to JsonArray(kept)))
            item.copy(fields = newFields, updated = now)
        }

    // ---- helpers ---------------------------------------------------------------

    private fun passkeyFields(
        rpId: String, rpName: String, credentialId: ByteArray, privateKeyJwk: String, publicKeyJwk: String,
        userHandle: ByteArray, userName: String, userDisplayName: String, createdAt: String,
    ): JsonObject = buildJsonObject {
        put("rpId", rpId)
        put("rpName", rpName)
        put("credentialId", b64u(credentialId))
        put("alg", -7)
        put("privateKey", privateKeyJwk)
        put("publicKey", publicKeyJwk)
        put("userHandle", b64u(userHandle))
        put("userName", userName)
        put("userDisplayName", userDisplayName)
        put("signCount", 0)
        put("createdAt", createdAt)
    }

    private fun parse(fields: JsonObject, source: Source, fallbackRpId: String): StoredPasskey? {
        val credB64 = fields["credentialId"]?.jsonPrimitive?.content ?: return null
        val privJwk = fields["privateKey"]?.jsonPrimitive?.content ?: return null
        val uhB64 = fields["userHandle"]?.jsonPrimitive?.content ?: return null
        val cred = runCatching { P256Key.b64uDecode(credB64) }.getOrNull() ?: return null
        val uh = runCatching { P256Key.b64uDecode(uhB64) }.getOrNull() ?: return null
        return StoredPasskey(
            rpId = fields["rpId"]?.jsonPrimitive?.content ?: fallbackRpId,
            credentialId = cred,
            privateKeyJwk = privJwk,
            userHandle = uh,
            userName = fields["userName"]?.jsonPrimitive?.content ?: "",
            userDisplayName = fields["userDisplayName"]?.jsonPrimitive?.content ?: "",
            source = source,
        )
    }

    private fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
