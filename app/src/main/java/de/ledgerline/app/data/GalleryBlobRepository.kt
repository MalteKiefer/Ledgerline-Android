package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.GalleryBlobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryBlobRepository private constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) : GalleryBlobs {
    @Inject constructor(sessionHolder: SessionHolder, vaultKeyHolder: VaultKeyHolder, crypto: Crypto) :
        this(sessionHolder, vaultKeyHolder, crypto, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    override suspend fun download(ref: String, key: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        try {
            val res = apiProvider(session).galleryRaw(ref)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            val bytes = res.body()!!.bytes()
            Outcome.Ok(BlobDownloader.decrypt(bytes, key, vk, crypto))
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
    }
}
