package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.Session
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryRepository private constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder, vaultKeyHolder: VaultKeyHolder, crypto: Crypto) :
        this(sessionHolder, vaultKeyHolder, crypto, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    private val json = Json { ignoreUnknownKeys = true }

    /** Gallery blob storage usage: (used bytes, quota bytes). Null on any failure. */
    suspend fun galleryUsage(): Pair<Long, Long>? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).galleryUsage()
            if (!res.isSuccessful) return null
            val body = res.body() ?: return null
            body.used to body.quota
        } catch (_: Exception) {
            null
        }
    }

    suspend fun load(): Outcome<Gallery> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        return try {
            val res = apiProvider(session).galleryStore()
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> Outcome.Err(ErrorKind.NETWORK)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        json.decodeFromString<GalleryManifest>(plain)
                    } ?: GalleryManifest()
                    Outcome.Ok(Gallery(manifest, body.version))
                }
            }
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }
}
