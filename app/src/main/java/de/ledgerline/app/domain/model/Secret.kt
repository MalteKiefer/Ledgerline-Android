package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Password-manager records, byte-compatible with the web (`resources/js/components/passwords.js`)
 * and iOS (`SecretItem.swift`) sealed `store/passwords` manifest. Like iOS, [SecretItem.fields]
 * is kept **opaque** ([JsonObject]) so every type-specific key (login/card/wifi/…) round-trips
 * losslessly regardless of what Android's UI understands. Nullable fields are omitted when
 * absent (`created`/`updated`/`trashed`/`folder`), matching the web's `encodeIfPresent`.
 */
@Serializable
data class SecretsManifest(
    val v: Int = 3,
    val secrets: List<SecretItem> = emptyList(),
    val secretFolders: List<SecretFolder> = emptyList(),
    val pwVaultMigrated: Boolean = false,
    /**
     * Foreign top-level manifest keys (e.g. `todoLists`/`todos`/`notes` from the pre-split
     * monolith, or any future module) captured verbatim on decode and re-emitted on seal so a
     * password save never erases another module's data — matching iOS `SecretsRepository.write`
     * which preserves every non-`secrets`/`secretFolders` key. Handled manually in
     * `PasswordsRepository`; `@Transient` keeps kotlinx from (de)serialising it as a nested key.
     */
    @Transient val extra: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SecretItem(
    val id: String = "",
    val type: String = "login",
    val title: String = "",
    val favorite: Boolean = false,
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val custom: List<CustomField> = emptyList(),
    val icon: String = "",
    val fields: JsonObject = JsonObject(emptyMap()),
    val created: String? = null,
    val updated: String? = null,
    val versions: List<SecretVersion> = emptyList(),
    /** ISO-8601 timestamp; presence = soft-deleted (in trash). */
    val trashed: String? = null,
) {
    val isTrashed: Boolean get() = !trashed.isNullOrEmpty()
}

/** A user-added custom field. `kind` ∈ text | secret | multiline | url. */
@Serializable
data class CustomField(val label: String = "", val value: String = "", val kind: String = "text")

/** A snapshot pushed when title/fields/custom change (newest-first, capped at 100). */
@Serializable
data class SecretVersion(
    val at: String = "",
    val title: String = "",
    val fields: JsonObject = JsonObject(emptyMap()),
    val custom: List<CustomField> = emptyList(),
)

/** A password vault. `role` ∈ read | edit | manage (personal = manage). */
@Serializable
data class SecretFolder(val id: String = "", val name: String = "", val role: String = "manage")

/** The decrypted secrets manifest + the server store version (optimistic concurrency). */
data class SecretsStore(val manifest: SecretsManifest, val version: Int)

/**
 * The 9 canonical secret types + their ordered field keys, mirroring the web `TYPES` registry
 * and iOS `SecretItem.SecretType`. [secretFieldKeys] are masked in the UI and drive versioning.
 */
object SecretTypes {
    /** Field keys whose values are secret (masked, revealable). */
    val secretFieldKeys = setOf("password", "totp", "cvv", "pin", "licensekey", "privateKey")

    /** Ordered field keys per type (for the detail/edit layout). */
    val fields: Map<String, List<String>> = mapOf(
        "login" to listOf("username", "password", "urls", "totp", "note"),
        "password" to listOf("password", "note"),
        "card" to listOf("cardholder", "number", "expiry", "cvv", "pin", "note"),
        "wifi" to listOf("ssid", "password", "security", "hidden", "note"),
        "license" to listOf("product", "licensekey", "owner", "email", "note"),
        "server" to listOf("host", "port", "username", "password", "note"),
        "passkey" to listOf("rpId", "userName", "userDisplayName", "note"),
        "identity" to listOf("firstName", "lastName", "email", "phone", "company", "street", "city", "state", "zip", "country", "note"),
        "secure_note" to listOf("note"),
    )

    /** Types the user can create (passkey is system-provisioned only). */
    val creatable = listOf("login", "password", "card", "wifi", "license", "server", "identity", "secure_note")

    val wifiSecurity = listOf("nopass", "WEP", "WPA", "WPA2", "WPA3", "WPA2-Enterprise", "WPA3-Enterprise")

    fun isSecretKey(key: String) = key in secretFieldKeys
}
