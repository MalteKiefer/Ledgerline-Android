package de.ledgerline.app.core.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The KDF params + wrapped Vault Key exactly as `GET /vault` returns them. Persisting this to disk
 * enables **offline passphrase unlock** (§11: the app must work fully offline). It leaks nothing
 * beyond what the server already holds in the zero-knowledge model: `salt`/`kdf_ops`/`kdf_mem` are
 * public KDF parameters, and `wrapped_vault_key`/`wrapped_vault_key_recovery` are ciphertext sealed
 * under the KEK (derived from the passphrase) / recovery key. Same trust level as [StoreDiskCache]
 * (which persists the VK-sealed store ciphertext): both need a client-held secret to be of any use,
 * and the Argon2id-SENSITIVE cost is the brute-force defense whether the wrapped key sits here or
 * on the server. Stored in app-private storage (allowBackup=false, data-extraction-rules exclude).
 */
@Serializable
data class CachedVaultParams(
    val configured: Boolean,
    val salt: String? = null,
    val kdfOps: Long? = null,
    val kdfMem: Long? = null,
    val wrappedVk: String? = null,
    val wrapNonce: String? = null,
    val hasRecovery: Boolean = false,
    val wrappedVkRecovery: String? = null,
    val recoveryNonce: String? = null,
)

@Singleton
class VaultParamsCache(private val file: File) {

    @Inject
    constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, "vault_params.json"))

    private val json = Json { ignoreUnknownKeys = true }

    /** Last cached params, or null if absent/corrupt (degrades to "not cached", never throws). */
    fun get(): CachedVaultParams? = try {
        if (!file.exists()) null else json.decodeFromString<CachedVaultParams>(file.readText())
    } catch (_: Exception) {
        null
    }

    /** Persist fresh params atomically (write to `.tmp`, then rename) after an online fetch. */
    fun put(p: CachedVaultParams) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedVaultParams.serializer(), p))
            tmp.renameTo(file)
        } catch (_: Exception) { /* best-effort; a failed cache write just means no offline unlock */ }
    }

    /** Erase the cached params (forced logout / wipe). */
    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }
}
