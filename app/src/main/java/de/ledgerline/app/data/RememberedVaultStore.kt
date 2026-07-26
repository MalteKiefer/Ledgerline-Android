package de.ledgerline.app.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.security.keystore.KeyProperties
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.domain.model.Session
import javax.crypto.Cipher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.rememberedVaultStore: DataStore<Preferences> by
    preferencesDataStore(name = "ledgerline_remembered_vault")

/** Session essentials + the Vault Key, unsealed together from the remembered blob. */
data class RememberedUnlock(val session: Session, val vk: ByteArray)

@Serializable
private data class SealedRemembered(
    val baseUrl: String,
    val token: String,
    val spkiPin: String,
    val userName: String?,
    val vkB64: String,
)

/**
 * Persists the Vault Key (with the session it belongs to) as one AES-256-GCM blob
 * sealed under a dedicated STRONG-biometric-only Keystore key. A single CryptoObject-
 * bound biometric prompt opens both, so the vault unlocks without the passphrase until
 * the stored expiry passes — after which the passphrase is required again and re-arms.
 *
 * This deliberately relaxes the "never persist the VK" rule (CLAUDE.md §9); it is opt-in,
 * biometrics-gated, hardware-key-bound, TTL-limited, and auto-invalidated when the user
 * enrolls a new biometric (setInvalidatedByBiometricEnrollment on the Keystore key).
 */
class RememberedVaultStore(private val context: Context) {
    // Own alias, STRONG-biometric only (no device-credential fallback) → a device PIN
    // alone can never unwrap the persisted VK.
    private val sealer = KeystoreSealer(
        alias = "ledgerline_vault_key_v1",
        requireAuth = true,
        authTypes = KeyProperties.AUTH_BIOMETRIC_STRONG,
    )
    private val blobKey = byteArrayPreferencesKey("remembered_blob")
    private val expiryKey = longPreferencesKey("remembered_expiry")
    private val json = Json

    /** A blob is present and its TTL has not passed (no biometric needed to check). */
    suspend fun hasValid(nowMillis: Long): Boolean {
        val prefs = context.rememberedVaultStore.data.first()
        return prefs[blobKey] != null && (prefs[expiryKey] ?: 0L) > nowMillis
    }

    /**
     * Seal [session] + [vk] and persist with an expiry of [expiryMillis]. Runs exactly
     * ONE STRONG biometric via [authorize]. @return true on success, false if cancelled.
     */
    suspend fun save(
        session: Session,
        vk: ByteArray,
        expiryMillis: Long,
        authorize: suspend (Cipher) -> Cipher?,
    ): Boolean {
        val plain = json.encodeToString(
            SealedRemembered(
                session.baseUrl,
                session.token,
                session.spkiPin,
                session.userName,
                Base64.encodeToString(vk, Base64.NO_WRAP),
            ),
        )
        val cipher = sealer.encryptCipher()
        val authed = authorize(cipher) ?: return false // ONE biometric here
        val blob = sealer.finishSeal(authed, plain.toByteArray())
        context.rememberedVaultStore.edit {
            it[blobKey] = blob
            it[expiryKey] = expiryMillis
        }
        return true
    }

    /**
     * Open the remembered blob (ONE STRONG biometric via [authorize]) and return the
     * session + VK. Returns null if absent, expired, or the auth/decrypt fails; a
     * decrypt failure (e.g. the key was invalidated by a new biometric enrollment)
     * also [clear]s the dead blob so the next unlock re-arms cleanly.
     */
    suspend fun open(nowMillis: Long, authorize: suspend (Cipher) -> Cipher?): RememberedUnlock? {
        val prefs = context.rememberedVaultStore.data.first()
        val blob = prefs[blobKey] ?: return null
        if ((prefs[expiryKey] ?: 0L) <= nowMillis) { clear(); return null }
        return try {
            val cipher = sealer.decryptCipher(blob)
            val authed = authorize(cipher) ?: return null // ONE biometric here
            // Re-check the TTL after the (blocking) biometric to close the check→use gap (L1).
            // Expiry is a soft re-prompt control; the hard gate is the biometric + Keystore key.
            if ((context.rememberedVaultStore.data.first()[expiryKey] ?: 0L) <= System.currentTimeMillis()) {
                clear(); return null
            }
            // Wipe the decrypted UTF-8 bytes once parsed (M2). The transient JSON String still
            // holds the base64 VK immutably — unavoidable without a binary format — but the
            // largest, longest-lived copy is zeroed.
            val plainBytes = sealer.finishOpen(authed, blob)
            val plain = String(plainBytes, Charsets.UTF_8)
            plainBytes.fill(0)
            val s = json.decodeFromString<SealedRemembered>(plain)
            RememberedUnlock(
                Session(s.baseUrl, s.token, s.spkiPin, s.userName),
                Base64.decode(s.vkB64, Base64.NO_WRAP),
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    /** Extend the TTL (no biometric, no re-seal) when a valid blob already exists. */
    suspend fun bumpExpiry(expiryMillis: Long) {
        context.rememberedVaultStore.edit { if (it[blobKey] != null) it[expiryKey] = expiryMillis }
    }

    /** Drop the persisted blob and delete the Keystore key (full disarm). */
    suspend fun clear() {
        context.rememberedVaultStore.edit {
            it.remove(blobKey)
            it.remove(expiryKey)
        }
        runCatching { sealer.clear() }
    }
}
