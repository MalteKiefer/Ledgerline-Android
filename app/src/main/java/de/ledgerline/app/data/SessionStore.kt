package de.ledgerline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_session")

@Serializable private data class SealedSession(val baseUrl: String, val token: String, val spkiPin: String, val userName: String?)

/** Persists the session as a single AES-GCM sealed blob (keystore-gated). */
class SessionStore(private val context: Context, private val sealer: KeystoreSealer) {
    private val key = byteArrayPreferencesKey("session_blob")
    private val json = Json

    suspend fun save(session: Session) {
        val plain = json.encodeToString(SealedSession(session.baseUrl, session.token, session.spkiPin, session.userName))
        val blob = sealer.seal(plain.toByteArray())
        context.dataStore.edit { it[key] = blob }
    }

    /** Requires the keystore key to be unlocked (BiometricPrompt) beforehand. */
    suspend fun load(): Session? {
        val blob = context.dataStore.data.first()[key] ?: return null
        val plain = String(sealer.open(blob))
        val s = json.decodeFromString<SealedSession>(plain)
        return Session(s.baseUrl, s.token, s.spkiPin, s.userName)
    }

    suspend fun clear() { context.dataStore.edit { it.remove(key) } }
    suspend fun exists(): Boolean = context.dataStore.data.first()[key] != null
}
