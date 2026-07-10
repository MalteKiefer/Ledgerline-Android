package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Loads + decrypts the workspace manifest over the pinned, authenticated session. */
@Singleton
class WorkspaceRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = NetworkFactory.create(session.baseUrl, tokenProvider = { session.token }, pin = session.spkiPin)
        return try {
            val res = api.store()
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> Outcome.Err(ErrorKind.NETWORK)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        json.decodeFromString<WorkspaceManifest>(plain)
                    } ?: WorkspaceManifest()
                    Outcome.Ok(Workspace(manifest, body.version))
                }
            }
        } catch (e: Exception) {
            Outcome.Err(ErrorKind.NETWORK, e)
        }
    }
}
