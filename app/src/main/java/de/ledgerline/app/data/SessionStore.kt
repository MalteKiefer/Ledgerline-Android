package de.ledgerline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.domain.model.Session
import javax.crypto.Cipher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_session")

@Serializable private data class SealedSession(val baseUrl: String, val token: String, val spkiPin: String, val userName: String?)

/**
 * Persists the session as a single AES-GCM sealed blob (keystore-gated with per-use
 * auth). [save]/[load] build the keystore cipher and hand it to [authorize], which
 * runs exactly ONE CryptoObject-bound biometric prompt and returns the authorised
 * cipher — that single prompt authorizes the keystore operation.
 */
class SessionStore(private val context: Context, private val sealer: KeystoreSealer) {
    private val key = byteArrayPreferencesKey("session_blob")
    private val json = Json

    /** @return true on success, false if the auth was cancelled/failed. */
    suspend fun save(session: Session, authorize: suspend (Cipher) -> Cipher?): Boolean {
        val plain = json.encodeToString(SealedSession(session.baseUrl, session.token, session.spkiPin, session.userName))
        val cipher = sealer.encryptCipher()
        val authed = authorize(cipher) ?: return false // ONE biometric happens here
        val blob = sealer.finishSeal(authed, plain.toByteArray())
        context.dataStore.edit { it[key] = blob }
        return true
    }

    /** Runs one CryptoObject-bound biometric (via [authorize]) to decrypt the blob. */
    suspend fun load(authorize: suspend (Cipher) -> Cipher?): Session? {
        val blob = context.dataStore.data.first()[key] ?: return null
        val cipher = sealer.decryptCipher(blob)
        val authed = authorize(cipher) ?: return null // ONE biometric happens here
        val plainBytes = sealer.finishOpen(authed, blob)
        val plain = String(plainBytes, Charsets.UTF_8)
        plainBytes.fill(0) // wipe the decrypted token bytes once parsed (M2)
        val s = json.decodeFromString<SealedSession>(plain)
        return Session(s.baseUrl, s.token, s.spkiPin, s.userName)
    }

    suspend fun clear() { context.dataStore.edit { it.remove(key) } }
    suspend fun exists(): Boolean = context.dataStore.data.first()[key] != null
}
