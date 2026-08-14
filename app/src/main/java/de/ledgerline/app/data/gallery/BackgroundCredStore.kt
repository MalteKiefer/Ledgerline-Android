package de.ledgerline.app.data.gallery

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.bgCredDataStore by preferencesDataStore("bg_cred")

/**
 * SECURITY DOWNGRADE, opt-in: a copy of the bearer token sealed with a Keystore key that does NOT
 * require per-use biometric (`requireAuth=false`), so the background backup [GalleryBackupWorker] can
 * upload while the app/device is locked. This deliberately weakens the normal biometric-sealed session
 * (`SessionStore`): a device compromise could read the token without a fingerprint. Only written while
 * "Background backup" is on; cleared on disable and on logout/wipe.
 */
@Singleton
class BackgroundCredStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val sealer = KeystoreSealer(alias = "ledgerline_bg_token_v1", requireAuth = false)
    private val key = stringPreferencesKey("session")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Cred(val baseUrl: String, val token: String, val spkiPin: String, val userName: String?)

    suspend fun save(session: Session) {
        val blob = sealer.seal(json.encodeToString(Cred.serializer(), Cred(session.baseUrl, session.token, session.spkiPin, session.userName)).toByteArray())
        context.bgCredDataStore.edit { it[key] = Base64.encodeToString(blob, Base64.NO_WRAP) }
    }

    suspend fun read(): Session? = runCatching {
        val enc = context.bgCredDataStore.data.first()[key] ?: return null
        val plain = sealer.open(Base64.decode(enc, Base64.NO_WRAP))
        val c = json.decodeFromString(Cred.serializer(), String(plain))
        Session(c.baseUrl, c.token, c.spkiPin, c.userName)
    }.getOrNull()

    suspend fun clear() { context.bgCredDataStore.edit { it.remove(key) } }
}
